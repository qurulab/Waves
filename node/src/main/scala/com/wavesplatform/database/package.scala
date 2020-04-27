package com.wavesplatform

import java.io.File
import java.nio.ByteBuffer
import java.util.{Map => JMap}

import com.google.common.base.Charsets.UTF_8
import com.google.common.io.ByteStreams.{newDataInput, newDataOutput}
import com.google.common.io.{ByteArrayDataInput, ByteArrayDataOutput}
import com.google.common.primitives.{Bytes, Ints, Longs, Shorts}
import com.google.protobuf.{ByteString, CodedInputStream, WireFormat}
import com.wavesplatform.account.PublicKey
import com.wavesplatform.api.BlockMeta
import com.wavesplatform.block.validation.Validators
import com.wavesplatform.block.{Block, BlockHeader}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.crypto._
import com.wavesplatform.database.protobuf.DataEntry.Value
import com.wavesplatform.database.{protobuf => pb}
import com.wavesplatform.lang.script.{Script, ScriptReader}
import com.wavesplatform.lang.v1.Serde
import com.wavesplatform.protobuf.block.PBBlocks
import com.wavesplatform.protobuf.transaction.{PBSignedTransaction, PBTransactions}
import com.wavesplatform.state._
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.transfer.TransferTransaction
import com.wavesplatform.transaction.{GenesisTransaction, LegacyPBSwitch, PaymentTransaction, Transaction, TransactionParsers, TxValidationError}
import com.wavesplatform.utils.{ScorexLogging, _}
import io.estatico.newtype.macros.newtype
import monix.eval.Task
import monix.reactive.Observable
import org.iq80.leveldb._

package object database extends ScorexLogging {
  def openDB(path: String, recreate: Boolean = false): DB = {
    log.debug(s"Open DB at $path")
    val file = new File(path)
    val options = new Options()
      .createIfMissing(true)
      .paranoidChecks(true)

    if (recreate) {
      LevelDBFactory.factory.destroy(file, options)
    }

    file.getAbsoluteFile.getParentFile.mkdirs()
    LevelDBFactory.factory.open(file, options)
  }

  final type DBEntry = JMap.Entry[Array[Byte], Array[Byte]]

  implicit class ByteArrayDataOutputExt(val output: ByteArrayDataOutput) extends AnyVal {
    def writeByteStr(s: ByteStr): Unit = {
      output.write(s.arr)
    }

    def writeScriptOption(v: Option[Script]): Unit = {
      output.writeBoolean(v.isDefined)
      v.foreach { s =>
        val b = s.bytes().arr
        output.writeShort(b.length)
        output.write(b)
      }
    }
  }

  implicit class ByteArrayDataInputExt(val input: ByteArrayDataInput) extends AnyVal {
    def readScriptOption(): Option[Script] = {
      if (input.readBoolean()) {
        val len = input.readShort()
        val b   = new Array[Byte](len)
        input.readFully(b)
        Some(ScriptReader.fromBytes(b).explicitGet())
      } else None
    }

    def readBytes(len: Int): Array[Byte] = {
      val arr = new Array[Byte](len)
      input.readFully(arr)
      arr
    }

    def readByteStr(len: Int): ByteStr = {
      ByteStr(readBytes(len))
    }

    def readSignature: ByteStr   = readByteStr(SignatureLength)
    def readPublicKey: PublicKey = PublicKey(readBytes(KeyLength))
  }

  def writeIntSeq(values: Seq[Int]): Array[Byte] = {
    values.foldLeft(ByteBuffer.allocate(4 * values.length))(_ putInt _).array()
  }

  def readIntSeq(data: Array[Byte]): Seq[Int] = Option(data).fold(Seq.empty[Int]) { d =>
    val in = ByteBuffer.wrap(data)
    Seq.fill(d.length / 4)(in.getInt)
  }

  def readAddressIds(data: Array[Byte]): Seq[AddressId] = Option(data).fold(Seq.empty[AddressId]) { d =>
    require(d.length % java.lang.Long.BYTES == 0, s"Invalid data length: ${d.length}")
    val buffer = ByteBuffer.wrap(data)
    Seq.fill(d.length / java.lang.Long.BYTES)(AddressId(buffer.getLong))
  }

  def writeAddressIds(values: Seq[AddressId]): Array[Byte] =
    values.foldLeft(ByteBuffer.allocate(values.length * java.lang.Long.BYTES)) { case (buf, aid) => buf.putLong(aid.toLong) }.array()

  def readTxIds(data: Array[Byte]): List[ByteStr] = Option(data).fold(List.empty[ByteStr]) { d =>
    val b   = ByteBuffer.wrap(d)
    val ids = List.newBuilder[ByteStr]

    while (b.remaining() > 0) {
      val buffer = b.get() match {
        case crypto.DigestLength    => new Array[Byte](crypto.DigestLength)
        case crypto.SignatureLength => new Array[Byte](crypto.SignatureLength)
      }
      b.get(buffer)
      ids += ByteStr(buffer)
    }

    ids.result()
  }

  def writeTxIds(ids: Seq[ByteStr]): Array[Byte] =
    ids
      .foldLeft(ByteBuffer.allocate(ids.map(_.arr.length + 1).sum)) {
        case (b, id) =>
          b.put(id.arr.length match {
              case crypto.DigestLength    => crypto.DigestLength.toByte
              case crypto.SignatureLength => crypto.SignatureLength.toByte
            })
            .put(id.arr)
      }
      .array()

  def readStrings(data: Array[Byte]): Seq[String] = Option(data).fold(Seq.empty[String]) { _ =>
    var i = 0
    val s = Seq.newBuilder[String]

    while (i < data.length) {
      val len = Shorts.fromByteArray(data.drop(i))
      s += new String(data, i + 2, len, UTF_8)
      i += (2 + len)
    }
    s.result()
  }

  def writeStrings(strings: Seq[String]): Array[Byte] =
    strings
      .foldLeft(ByteBuffer.allocate(strings.map(_.utf8Bytes.length + 2).sum)) {
        case (b, s) =>
          val bytes = s.utf8Bytes
          b.putShort(bytes.length.toShort).put(bytes)
      }
      .array()

  def writeLeaseBalance(lb: LeaseBalance): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(lb.in)
    ndo.writeLong(lb.out)
    ndo.toByteArray
  }

  def readLeaseBalance(data: Array[Byte]): LeaseBalance = Option(data).fold(LeaseBalance.empty) { d =>
    val ndi = newDataInput(d)
    LeaseBalance(ndi.readLong(), ndi.readLong())
  }

  def readVolumeAndFee(data: Array[Byte]): VolumeAndFee = Option(data).fold(VolumeAndFee.empty) { d =>
    val ndi = newDataInput(d)
    VolumeAndFee(ndi.readLong(), ndi.readLong())
  }

  def writeVolumeAndFee(vf: VolumeAndFee): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(vf.volume)
    ndo.writeLong(vf.fee)
    ndo.toByteArray
  }

  def readTransactionInfo(data: Array[Byte]): (Int, Transaction) =
    (Ints.fromByteArray(data), TransactionParsers.parseBytes(data.drop(4)).get)

  def readTransactionHeight(data: Array[Byte]): Int = Ints.fromByteArray(data)

  def writeTransactionInfo(txInfo: (Int, Transaction)): Array[Byte] = {
    val (h, tx) = txInfo
    val txBytes = tx.bytes()
    ByteBuffer.allocate(4 + txBytes.length).putInt(h).put(txBytes).array()
  }

  def readTransactionIds(data: Array[Byte]): Seq[(Int, ByteStr)] = Option(data).fold(Seq.empty[(Int, ByteStr)]) { d =>
    val b   = ByteBuffer.wrap(d)
    val ids = Seq.newBuilder[(Int, ByteStr)]
    while (b.hasRemaining) {
      ids += b.get.toInt -> {
        val buf = new Array[Byte](b.get)
        b.get(buf)
        ByteStr(buf)
      }
    }
    ids.result()
  }

  def writeTransactionIds(ids: Seq[(Int, ByteStr)]): Array[Byte] = {
    val size   = ids.foldLeft(0) { case (prev, (_, id)) => prev + 2 + id.arr.length }
    val buffer = ByteBuffer.allocate(size)
    for ((typeId, id) <- ids) {
      buffer.put(typeId.toByte).put(id.arr.length.toByte).put(id.arr)
    }
    buffer.array()
  }

  def readFeatureMap(data: Array[Byte]): Map[Short, Int] = Option(data).fold(Map.empty[Short, Int]) { _ =>
    val b        = ByteBuffer.wrap(data)
    val features = Map.newBuilder[Short, Int]
    while (b.hasRemaining) {
      features += b.getShort -> b.getInt
    }

    features.result()
  }

  def writeFeatureMap(features: Map[Short, Int]): Array[Byte] = {
    val b = ByteBuffer.allocate(features.size * 6)
    for ((featureId, height) <- features)
      b.putShort(featureId).putInt(height)

    b.array()
  }

  def readContinuationStates(bytes: Array[Byte]): Map[ByteStr, ContinuationState] = {
    if (bytes == null || bytes.isEmpty)
      Map()
    else {
      val input = newDataInput(bytes)
      val size  = input.readInt()
      (1 to size).map { _ =>
        val invokeTxIdLength = input.readByte()
        val invokeTxId       = input.readByteStr(invokeTxIdLength)

        val exprBytesLength = input.readInt()
        val exprBytes       = input.readBytes(exprBytesLength)
        val expr            = Serde.deserialize(exprBytes).explicitGet()._1
        (invokeTxId, ContinuationState.InProgress(expr))
      }.toMap
    }
  }

  def writeContinuationStates(states: Map[ByteStr, ContinuationState]): Array[Byte] = {
    val output     = newDataOutput()
    val unfinished = states.collect { case (invokeTxId, ContinuationState.InProgress(expr)) => (invokeTxId, expr) }
    output.writeInt(unfinished.size)
    unfinished.foreach {
      case (invokeTxId, expr) =>
        output.writeByte(invokeTxId.length)
        output.writeByteStr(invokeTxId)

        val exprBytes = Serde.serialize(expr, allowObjects = true)
        output.writeInt(exprBytes.length)
        output.write(exprBytes)
    }
    output.toByteArray
  }

  def readSponsorship(data: Array[Byte]): SponsorshipValue = {
    val ndi = newDataInput(data)
    SponsorshipValue(ndi.readLong())
  }

  def writeSponsorship(ai: SponsorshipValue): Array[Byte] = {
    val ndo = newDataOutput()
    ndo.writeLong(ai.minFee)
    ndo.toByteArray
  }

  def readAssetDetails(data: Array[Byte]): (AssetInfo, AssetVolumeInfo) = {

    val pbad = pb.AssetDetails.parseFrom(data)

    (
      AssetInfo(pbad.name, pbad.description, Height(pbad.lastRenamedAt)),
      AssetVolumeInfo(pbad.reissuable, BigInt(pbad.totalVolume.toByteArray))
    )
  }

  def writeAssetDetails(ai: (AssetInfo, AssetVolumeInfo)): Array[Byte] = {
    val (info, volumeInfo) = ai

    pb.AssetDetails(
        info.name,
        info.description,
        info.lastUpdatedAt,
        volumeInfo.isReissuable,
        ByteString.copyFrom(volumeInfo.volume.toByteArray)
      )
      .toByteArray
  }

  def writeAssetStaticInfo(sai: AssetStaticInfo): Array[Byte] =
    pb.StaticAssetInfo(
        ByteString.copyFrom(sai.source.arr),
        ByteString.copyFrom(sai.issuer.arr),
        sai.decimals,
        sai.nft
      )
      .toByteArray

  def readAssetStaticInfo(bb: Array[Byte]): AssetStaticInfo = {
    val sai = pb.StaticAssetInfo.parseFrom(bb)
    AssetStaticInfo(
      TransactionId(ByteStr(sai.sourceId.toByteArray)),
      PublicKey(sai.issuerPublicKey.toByteArray),
      sai.decimals,
      sai.isNft
    )
  }

  def writeBlockMeta(data: BlockMeta): Array[Byte] =
    pb.BlockMeta(
        Some(PBBlocks.protobuf(data.header)),
        ByteString.copyFrom(data.signature),
        data.headerHash.fold(ByteString.EMPTY)(hh => ByteString.copyFrom(hh)),
        data.height,
        data.size,
        data.transactionCount,
        data.totalFeeInWaves,
        data.reward.getOrElse(-1L),
        data.vrf.fold(ByteString.EMPTY)(vrf => ByteString.copyFrom(vrf))
      )
      .toByteArray

  def readBlockMeta(height: Int)(bs: Array[Byte]): BlockMeta = {
    val pbbm = pb.BlockMeta.parseFrom(bs)
    BlockMeta(
      PBBlocks.vanilla(pbbm.header.get),
      ByteStr(pbbm.signature.toByteArray),
      Option(pbbm.headerHash).collect { case bs if !bs.isEmpty => ByteStr(bs.toByteArray) },
      pbbm.height,
      pbbm.size,
      pbbm.transactionCount,
      pbbm.totalFeeInWaves,
      Option(pbbm.reward).filter(_ >= 0),
      Option(pbbm.vrf).collect { case bs if !bs.isEmpty => ByteStr(bs.toByteArray) }
    )
  }

  def readTransactionHNSeqAndType(bs: Array[Byte]): (Height, Seq[(Byte, TxNum)]) = {
    val ndi          = newDataInput(bs)
    val height       = Height(ndi.readInt())
    val numSeqLength = ndi.readInt()

    (height, List.fill(numSeqLength) {
      val tp  = ndi.readByte()
      val num = TxNum(ndi.readShort())
      (tp, num)
    })
  }

  def writeTransactionHNSeqAndType(v: (Height, Seq[(Byte, TxNum)])): Array[Byte] = {
    val (height, numSeq) = v
    val numSeqLength     = numSeq.length

    val outputLength = 4 + 4 + numSeqLength * (4 + 1)
    val ndo          = newDataOutput(outputLength)

    ndo.writeInt(height)
    ndo.writeInt(numSeqLength)
    numSeq.foreach {
      case (tp, num) =>
        ndo.writeByte(tp)
        ndo.writeShort(num)
    }

    ndo.toByteArray
  }

  def readTransactionHN(bs: Array[Byte]): (Height, TxNum) = {
    val ndi = newDataInput(bs)
    val h   = Height(ndi.readInt())
    val num = TxNum(ndi.readShort())

    (h, num)
  }

  def writeTransactionHN(v: (Height, TxNum)): Array[Byte] = {
    val ndo = newDataOutput(8)

    val (h, num) = v

    ndo.writeInt(h)
    ndo.writeShort(num)

    ndo.toByteArray
  }

  def readDataEntry(key: String)(bs: Array[Byte]): DataEntry[_] =
    pb.DataEntry.parseFrom(bs).value match {
      case Value.Empty              => EmptyDataEntry(key)
      case Value.IntValue(value)    => IntegerDataEntry(key, value)
      case Value.BoolValue(value)   => BooleanDataEntry(key, value)
      case Value.BinaryValue(value) => BinaryDataEntry(key, ByteStr(value.toByteArray))
      case Value.StringValue(value) => StringDataEntry(key, value)
    }

  def writeDataEntry(e: DataEntry[_]): Array[Byte] =
    pb.DataEntry(e match {
        case IntegerDataEntry(_, value) => pb.DataEntry.Value.IntValue(value)
        case BooleanDataEntry(_, value) => pb.DataEntry.Value.BoolValue(value)
        case BinaryDataEntry(_, value)  => pb.DataEntry.Value.BinaryValue(ByteString.copyFrom(value.arr))
        case StringDataEntry(_, value)  => pb.DataEntry.Value.StringValue(value)
        case _: EmptyDataEntry          => pb.DataEntry.Value.Empty
      })
      .toByteArray

  implicit class EntryExt(val e: JMap.Entry[Array[Byte], Array[Byte]]) extends AnyVal {
    import com.wavesplatform.crypto.DigestLength
    def extractId(offset: Int = 2, length: Int = DigestLength): ByteStr = {
      val id = ByteStr(new Array[Byte](length))
      Array.copy(e.getKey, offset, id.arr, 0, length)
      id
    }
  }

  implicit class DBExt(val db: DB) extends AnyVal {
    def readOnly[A](f: ReadOnlyDB => A): A = {
      val snapshot = db.getSnapshot
      try f(new ReadOnlyDB(db, new ReadOptions().snapshot(snapshot)))
      finally snapshot.close()
    }

    /**
      * @note Runs operations in batch, so keep in mind, that previous changes don't appear lately in f
      */
    def readWrite[A](f: RW => A): A = {
      val snapshot    = db.getSnapshot
      val readOptions = new ReadOptions().snapshot(snapshot)
      val batch       = new SortedBatch
      val rw          = new RW(db, readOptions, batch)
      val nativeBatch = db.createWriteBatch()
      try {
        val r = f(rw)
        batch.addedEntries.foreach { case (k, v) => nativeBatch.put(k.arr, v) }
        batch.deletedEntries.foreach(k => nativeBatch.delete(k.arr))
        db.write(nativeBatch, new WriteOptions().sync(false).snapshot(false))
        r
      } finally {
        nativeBatch.close()
        snapshot.close()
      }
    }

    def get[A](key: Key[A]): A                           = key.parse(db.get(key.keyBytes))
    def get[A](key: Key[A], readOptions: ReadOptions): A = key.parse(db.get(key.keyBytes, readOptions))
    def has(key: Key[_]): Boolean                        = db.get(key.keyBytes) != null

    def iterateOver(tag: KeyTags.KeyTag)(f: DBEntry => Unit): Unit = iterateOver(tag.prefixBytes)(f)

    def iterateOver(prefix: Array[Byte], seekPrefix: Array[Byte] = Array.emptyByteArray)(f: DBEntry => Unit): Unit = {
      val iterator = db.iterator()
      try {
        iterator.seek(Bytes.concat(prefix, seekPrefix))
        while (iterator.hasNext && iterator.peekNext().getKey.startsWith(prefix)) f(iterator.next())
      } finally iterator.close()
    }

    def resourceObservable: Observable[DBResource] = Observable.resource(Task(DBResource(db)))(r => Task(r.close()))

    def withResource[A](f: DBResource => A): A = {
      val resource = DBResource(db)
      try f(resource)
      finally resource.close()
    }
  }

  def createBlock(header: BlockHeader, signature: ByteStr, txs: Seq[Transaction]): Either[TxValidationError.GenericError, Block] =
    Validators.validateBlock(Block(header, signature, txs))

  def writeAssetScript(script: (Script, Long)): Array[Byte] =
    Longs.toByteArray(script._2) ++ script._1.bytes().arr

  def readAssetScript(b: Array[Byte]): (Script, Long) =
    ScriptReader.fromBytes(b.drop(8)).explicitGet() -> Longs.fromByteArray(b)

  def writeAccountScriptInfo(scriptInfo: AccountScriptInfo): Array[Byte] =
    pb.AccountScriptInfo.toByteArray(
      pb.AccountScriptInfo(
        ByteString.copyFrom(scriptInfo.publicKey.arr),
        ByteString.copyFrom(scriptInfo.script.bytes()),
        scriptInfo.verifierComplexity,
        scriptInfo.complexitiesByEstimator.map {
          case (version, complexities) =>
            pb.AccountScriptInfo.ComplexityByVersion(version, complexities)
        }.toSeq
      )
    )

  def readAccountScriptInfo(b: Array[Byte]): AccountScriptInfo = {
    val asi = pb.AccountScriptInfo.parseFrom(b)
    AccountScriptInfo(
      PublicKey(asi.publicKey.toByteArray),
      ScriptReader.fromBytes(asi.scriptBytes.toByteArray).explicitGet(),
      asi.maxComplexity,
      asi.callableComplexity.map { c =>
        c.version -> c.callableComplexity
      }.toMap
    )
  }

  def readTransaction(b: Array[Byte]): (Transaction, Boolean) = {
    import pb.TransactionData.Transaction._

    val data = pb.TransactionData.parseFrom(b)
    data.transaction match {
      case tx: LegacyBytes    => (TransactionParsers.parseBytes(tx.value.toByteArray).get, !data.failed)
      case tx: NewTransaction => (PBTransactions.vanilla(tx.value).explicitGet(), !data.failed)
      case _                  => throw new IllegalArgumentException("Illegal transaction data")
    }
  }

  def writeTransaction(v: (Transaction, Boolean)): Array[Byte] = {
    import pb.TransactionData.Transaction._
    val (tx, succeed) = v
    val ptx = tx match {
      case lps: LegacyPBSwitch if !lps.isProtobufVersion => LegacyBytes(ByteString.copyFrom(tx.bytes()))
      case _: GenesisTransaction                         => LegacyBytes(ByteString.copyFrom(tx.bytes()))
      case _: PaymentTransaction                         => LegacyBytes(ByteString.copyFrom(tx.bytes()))
      case _                                             => NewTransaction(PBTransactions.protobuf(tx))
    }
    pb.TransactionData(!succeed, ptx).toByteArray
  }

  /** Returns status (succeed - true, failed -false) and bytes (left - legacy format bytes, right - new format bytes) */
  def readTransactionBytes(b: Array[Byte]): (Boolean, Either[Array[Byte], Array[Byte]]) = {
    import pb.TransactionData._

    val coded = CodedInputStream.newInstance(b)

    @inline def validTransactionFieldNum(fieldNum: Int): Boolean = fieldNum == NEW_TRANSACTION_FIELD_NUMBER || fieldNum == LEGACY_BYTES_FIELD_NUMBER
    @inline def readBytes(fieldNum: Int): Either[Array[Byte], Array[Byte]] = {
      val size  = coded.readUInt32()
      val bytes = coded.readRawBytes(size)
      if (fieldNum == NEW_TRANSACTION_FIELD_NUMBER) Right(bytes) else Left(bytes)
    }

    val transactionFieldTag  = coded.readTag()
    val transactionFieldNum  = WireFormat.getTagFieldNumber(transactionFieldTag)
    val transactionFieldType = WireFormat.getTagWireType(transactionFieldTag)
    require(validTransactionFieldNum(transactionFieldNum), "Unknown `transaction` field in transaction data")
    require(transactionFieldType == WireFormat.WIRETYPE_LENGTH_DELIMITED, "Can't parse `transaction` field in transaction data")
    val bytes = readBytes(WireFormat.getTagFieldNumber(transactionFieldTag))

    val succeed =
      if (coded.isAtEnd) true
      else {
        val statusFieldTag  = coded.readTag()
        val statusFieldNum  = WireFormat.getTagFieldNumber(statusFieldTag)
        val statusFieldType = WireFormat.getTagWireType(statusFieldTag)
        require(statusFieldNum == FAILED_FIELD_NUMBER, "Unknown `failed` field in transaction data")
        require(statusFieldType == WireFormat.WIRETYPE_VARINT, "Can't parse `failed` field in transaction data")
        !coded.readBool()
      }

    (succeed, bytes)
  }

  def readTransferTransaction(b: Array[Byte]): Option[TransferTransaction] =
    readTransactionBytes(b) match {
      case (true, Left(oldBytes)) => TransferTransaction.parseBytes(oldBytes).toOption
      case (true, Right(bytes))   => PBTransactions.vanilla(PBSignedTransaction.parseFrom(bytes)).toOption.collect { case t: TransferTransaction => t }
      case _                      => None
    }

  def loadBlock(height: Height, db: ReadOnlyDB): Option[Block] =
    for {
      meta <- db.get(Keys.blockMetaAt(height))
      txs = (0 until meta.transactionCount).toList.flatMap { n =>
        db.get(Keys.transactionAt(height, TxNum(n.toShort)))
      }
      block <- createBlock(meta.header, meta.signature, txs.map(_._1)).toOption
    } yield block

  def fromHistory[A](resource: DBResource, historyKey: Key[Seq[Int]], valueKey: Int => Key[A]): Option[A] =
    for {
      h <- resource.get(historyKey).headOption
    } yield resource.get(valueKey(h))

  def loadAssetDescription(resource: DBResource, asset: IssuedAsset): Option[AssetDescription] =
    for {
      staticInfo         <- resource.get(Keys.assetStaticInfo(asset))
      (info, volumeInfo) <- fromHistory(resource, Keys.assetDetailsHistory(asset), Keys.assetDetails(asset))
      sponsorship = fromHistory(resource, Keys.sponsorshipHistory(asset), Keys.sponsorship(asset)).fold(0L)(_.minFee)
      script      = fromHistory(resource, Keys.assetScriptHistory(asset), Keys.assetScript(asset)).flatten
    } yield AssetDescription(
      staticInfo.source,
      staticInfo.issuer,
      info.name,
      info.description,
      staticInfo.decimals,
      volumeInfo.isReissuable,
      volumeInfo.volume,
      info.lastUpdatedAt,
      script,
      sponsorship,
      staticInfo.nft
    )

  @newtype case class AddressId(toLong: Long) {
    def toByteArray: Array[Byte] = toLong.toByteArray
  }

  object AddressId {
    def fromByteArray(bs: Array[Byte]): AddressId = AddressId(Longs.fromByteArray(bs))
  }

  implicit class LongExt(val l: Long) extends AnyVal {
    def toByteArray: Array[Byte] = Longs.toByteArray(l)
  }
}
