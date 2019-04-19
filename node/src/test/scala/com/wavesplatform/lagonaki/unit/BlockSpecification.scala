package com.wavesplatform.lagonaki.unit

import com.wavesplatform.account.PublicKey
import com.wavesplatform.block.{Block, BlockHeader, SignerData}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.EitherExt2
import com.wavesplatform.consensus.nxt.NxtLikeConsensusBlockData
import com.wavesplatform.metrics.Instrumented
import com.wavesplatform.state.diffs.produce
import com.wavesplatform.transaction._
import com.wavesplatform.transaction.transfer._
import com.wavesplatform.utils.Merkle
import com.wavesplatform.{NoShrink, TransactionGen, crypto}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest._
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}
import scorex.crypto.hash.Digest32

import scala.util.{Random, Success}

class BlockSpecification extends PropSpec with PropertyChecks with TransactionGen with Matchers with NoShrink {

  val time = System.currentTimeMillis() - 5000

  def bigBlockGen(amt: Int): Gen[Block] =
    for {
      baseTarget          <- arbitrary[Long]
      reference           <- byteArrayGen(Block.BlockIdLength).map(r => ByteStr(r))
      generationSignature <- byteArrayGen(Block.GeneratorSignatureLength)
      assetBytes          <- byteArrayGen(AssetIdLength)
      assetId = Some(ByteStr(assetBytes))
      sender                                    <- accountGen
      recipient                                 <- accountGen
      paymentTransaction: TransferTransactionV1 <- wavesTransferGeneratorP(time, sender, recipient)
    } yield
      Block
        .buildAndSign(
          3,
          time,
          reference,
          NxtLikeConsensusBlockData(baseTarget, ByteStr(generationSignature)),
          Seq.fill(amt)(paymentTransaction),
          transactionTreeHash = Merkle.EMPTY_ROOT_HASH,
          minerBalancesTreeHash = Merkle.EMPTY_ROOT_HASH,
          minerEffectiveBalancesTreeHash = Merkle.EMPTY_ROOT_HASH,
          recipient,
          Set.empty
        )
        .explicitGet()

  property(" block with txs bytes/parse roundtrip version 1,2") {
    Seq[Byte](1, 2).foreach { version =>
      forAll(blockGen) {
        case (baseTarget, reference, generationSignature, recipient, transactionData) =>
          val block = Block
            .buildAndSign(
              version,
              time,
              reference,
              NxtLikeConsensusBlockData(baseTarget, generationSignature),
              transactionData,
              Merkle.EMPTY_ROOT_HASH,
              Merkle.EMPTY_ROOT_HASH,
              Merkle.EMPTY_ROOT_HASH,
              recipient,
              Set.empty
            )
            .explicitGet()
          val parsedBlock = Block.parseBytes(block.bytes()).get
          assert(block.signaturesValid().isRight)
          assert(parsedBlock.signaturesValid().isRight)
          assert(parsedBlock.consensusData.generationSignature == generationSignature)
          assert(parsedBlock.version.toInt == version)
          assert(parsedBlock.signerData.generator == recipient.publicKey)
      }
    }
  }

  property(" block version 1,2 could not contain feature votes") {
    Seq[Byte](1, 2).foreach { version =>
      forAll(blockGen) {
        case (baseTarget, reference, generationSignature, recipient, transactionData) =>
          Block.buildAndSign(
            version,
            time,
            reference,
            NxtLikeConsensusBlockData(baseTarget, generationSignature),
            transactionData,
            Merkle.EMPTY_ROOT_HASH,
            Merkle.EMPTY_ROOT_HASH,
            Merkle.EMPTY_ROOT_HASH,
            recipient,
            Set(1)
          ) should produce("could not contain feature votes")
      }
    }
  }

  property("header roundtrip") {
    forAll(blockGen) {
      case (baseTarget, reference, generationSignature, recipient, transactionData) =>
        val randomHash = Digest32 @@ new Array[Byte](32)

        Random.nextBytes(randomHash)

        val block = Block
          .buildAndSign(
            3,
            time,
            reference,
            NxtLikeConsensusBlockData(baseTarget, generationSignature),
            transactionData,
            randomHash,
            randomHash,
            randomHash,
            recipient,
            Set.empty
          )
          .explicitGet()

        val headerBytes = block.headerBytes()

        BlockHeader.parseWithoutTransactions(headerBytes) shouldBe an[Success[_]]
    }
  }

  property(s" feature flags limit is ${Block.MaxFeaturesInBlock}") {
    val version           = 3.toByte
    val supportedFeatures = (0 to Block.MaxFeaturesInBlock * 2).map(_.toShort).toSet

    forAll(blockGen) {
      case (baseTarget, reference, generationSignature, recipient, transactionData) =>
        Block.buildAndSign(
          version,
          time,
          reference,
          NxtLikeConsensusBlockData(baseTarget, generationSignature),
          transactionData,
          Merkle.EMPTY_ROOT_HASH,
          Merkle.EMPTY_ROOT_HASH,
          Merkle.EMPTY_ROOT_HASH,
          recipient,
          supportedFeatures
        ) should produce(s"Block could not contain more than ${Block.MaxFeaturesInBlock} feature votes")
    }
  }
  property(" block with txs bytes/parse roundtrip version 3") {
    val version = 3.toByte

    val faetureSetGen: Gen[Set[Short]] = Gen.choose(0, Block.MaxFeaturesInBlock).flatMap(fc => Gen.listOfN(fc, arbitrary[Short])).map(_.toSet)

    forAll(blockGen, faetureSetGen) {
      case ((baseTarget, reference, generationSignature, recipient, transactionData), featureVotes) =>
        val block = Block
          .buildAndSign(
            version,
            time,
            reference,
            NxtLikeConsensusBlockData(baseTarget, generationSignature),
            transactionData,
            Merkle.EMPTY_ROOT_HASH,
            Merkle.EMPTY_ROOT_HASH,
            Merkle.EMPTY_ROOT_HASH,
            recipient,
            featureVotes
          )
          .explicitGet()
        val parsedBlock = Block.parseBytes(block.bytes()).get
        assert(block.signaturesValid().isRight)
        assert(parsedBlock.signaturesValid().isRight)
        assert(parsedBlock.consensusData.generationSignature == generationSignature)
        assert(parsedBlock.version.toInt == version)
        assert(parsedBlock.signerData.generator == recipient.publicKey)
        assert(parsedBlock.featureVotes == featureVotes)
    }
  }

  property("block signed by a weak public key is invalid") {
    val weakAccount = PublicKey(Array.fill(32)(0: Byte))
    forAll(blockGen) {
      case (baseTarget, reference, generationSignature, recipient, transactionData) =>
        val block = Block
          .build(
            3,
            time,
            reference,
            NxtLikeConsensusBlockData(baseTarget, generationSignature),
            transactionData,
            Merkle.EMPTY_ROOT_HASH,
            Merkle.EMPTY_ROOT_HASH,
            Merkle.EMPTY_ROOT_HASH,
            SignerData(weakAccount, ByteStr(Array.fill(64)(0: Byte))),
            Set.empty
          )
          .explicitGet()
        block.signaturesValid() shouldBe 'left
    }
  }

  ignore("sign time for 60k txs") {
    forAll(randomTransactionsGen(60000), accountGen, byteArrayGen(Block.BlockIdLength), byteArrayGen(Block.GeneratorSignatureLength)) {
      case ((txs, acc, ref, gs)) =>
        val (block, t0) =
          Instrumented.withTimeMillis(
            Block
              .buildAndSign(3,
                            1,
                            ByteStr(ref),
                            NxtLikeConsensusBlockData(1, ByteStr(gs)),
                            txs,
                            Merkle.EMPTY_ROOT_HASH,
                            Merkle.EMPTY_ROOT_HASH,
                            Merkle.EMPTY_ROOT_HASH,
                            acc,
                            Set.empty)
              .explicitGet())
        val (bytes, t1) = Instrumented.withTimeMillis(block.bytesWithoutSignature())
        val (hash, t2)  = Instrumented.withTimeMillis(crypto.fastHash(bytes))
        val (sig, t3)   = Instrumented.withTimeMillis(crypto.sign(acc, hash))
        println((t0, t1, t2, t3))
    }
  }

  ignore("serialize and deserialize big block") {
    forAll(bigBlockGen(100 * 1000)) {
      case block =>
        val parsedBlock = Block.parseBytes(block.bytes()).get
        block.signaturesValid() shouldBe 'right
        parsedBlock.signaturesValid() shouldBe 'right
    }
  }
}