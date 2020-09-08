package com.wavesplatform.lang.script

import cats.implicits._
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.lang.contract.DApp
import com.wavesplatform.lang.directives.values.{StdLibVersion, DApp => DAppType}
import com.wavesplatform.lang.utils.{functionNativeCosts, _}
import com.wavesplatform.lang.v1.ContractLimits._
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.compiler.Terms._
import com.wavesplatform.lang.v1.estimator.ScriptEstimator
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.lang.v1.{BaseGlobal, FunctionHeader}
import monix.eval.Coeval

object ContractScript {

  private val Global: BaseGlobal = com.wavesplatform.lang.Global // Hack for IDEA

  def validateBytes(bs: Array[Byte]): Either[String, Unit] =
    Either.cond(
      bs.length <= MaxContractSizeInBytes,
      (),
      s"Script is too large: ${bs.length} bytes > $MaxContractSizeInBytes bytes"
    )

  def apply(version: StdLibVersion, contract: DApp): Either[String, Script] =
    ContractScriptImpl(version, contract)
      .asRight[String]
      .flatTap(s => validateBytes(s.bytes().arr))

  case class ContractScriptImpl(stdLibVersion: StdLibVersion, expr: DApp) extends Script {
    override type Expr = DApp
    override val bytes: Coeval[ByteStr] = Coeval.fromTry(
      Global
        .serializeContract(expr, stdLibVersion)
        .bimap(new RuntimeException(_), ByteStr(_))
        .toTry
    )
    override val containsBlockV2: Coeval[Boolean] = Coeval.evalOnce(true)

    override val containsArray: Boolean = {
      val declExprs = expr.decs.map {
        case l: LET        => l.value
        case f: FUNC       => f.body
        case _: FAILED_DEC => FAILED_EXPR()
      }
      val callableExprs = expr.callableFuncs.map(_.u.body)
      val verifierExpr  = expr.verifierFuncOpt.map(_.u.body).toList

      (verifierExpr ::: declExprs ::: callableExprs)
        .exists(com.wavesplatform.lang.v1.compiler.containsArray)
    }
  }

  private def estimateAnnotatedFunctions(
      version: StdLibVersion,
      dApp: DApp,
      estimator: ScriptEstimator
  ): Either[String, List[(String, Long)]] =
    estimateDeclarations(
      dApp,
      estimator(varNames(version, DAppType), functionCosts(version), _),
      annotatedFunctions(dApp)
    )

  def estimateGlobalVariables(
      version: StdLibVersion,
      dApp: DApp,
      estimator: ScriptEstimator
  ): Either[String, List[(String, Long)]] =
    estimateDeclarations(
      dApp,
      estimator(varNames(version, DAppType), functionCosts(version), _),
      dApp.decs.collect { case l: LET => (None, l) }
    )

  def estimateUserFunctions(
      version: StdLibVersion,
      dApp: DApp,
      estimator: ScriptEstimator
  ): Either[String, List[(String, Long)]] =
    estimateDeclarations(
      dApp,
      estimator(varNames(version, DAppType), functionCosts(version), _),
      dApp.decs.collect { case f: FUNC => (None, f) }
    )

  private def annotatedFunctions(dApp: DApp): List[(Some[String], FUNC)] =
    (dApp.verifierFuncOpt ++ dApp.callableFuncs)
      .map(func => (Some(func.annotation.invocationArgName), func.u))
      .toList

  private def estimateDeclarations(
      dApp: DApp,
      estimator: EXPR => Either[String, Long],
      declarations: List[(Option[String], DECLARATION)]
  ): Either[String, List[(String, Long)]] =
    declarations.traverse {
      case (annotationArgName, declarationExpression) =>
        val expr = constructExprFromDeclAndContext(dApp.decs, annotationArgName, declarationExpression)
        estimator(expr).map((declarationExpression.name, _))
    }

  private[script] def constructExprFromDeclAndContext(
      dec: List[DECLARATION],
      annotationArgNameOpt: Option[String],
      decl: DECLARATION
  ): EXPR = {
    val declExpr =
      decl match {
        case let @ LET(name, _, _) =>
          BLOCK(let, REF(name))
        case func @ FUNC(name, args, _) =>
          BLOCK(
            func,
            FUNCTION_CALL(FunctionHeader.User(name), List.fill(args.size)(TRUE))
          )
        case Terms.FAILED_DEC() =>
          FAILED_EXPR()
      }
    val funcWithContext =
      annotationArgNameOpt.fold(declExpr)(
        annotationArgName => BLOCK(LET(annotationArgName, TRUE), declExpr)
      )
    dec.foldRight(funcWithContext)((declaration, expr) => BLOCK(declaration, expr))
  }

  def estimateComplexity(
      version: StdLibVersion,
      dApp: DApp,
      estimator: ScriptEstimator,
      useReducedVerifierLimit: Boolean = true,
      allowContinuation: Boolean = false
  ): Either[String, (Long, Map[String, Long])] =
    for {
      (maxComplexity, complexities) <- estimateComplexityExact(version, dApp, estimator)
      _                             <- checkComplexity(version, dApp, maxComplexity, complexities, useReducedVerifierLimit, allowContinuation)
    } yield (maxComplexity._2, complexities)

  def checkComplexity(
      version: StdLibVersion,
      dApp: DApp,
      maxComplexity: (String, Long),
      complexities: Map[String, Long],
      useReducedVerifierLimit: Boolean,
      allowContinuation: Boolean = false
  ): Either[String, Unit] =
    for {
      _ <- if (useReducedVerifierLimit) estimateVerifierReduced(dApp, complexities, version) else Right(())
      limit = if (allowContinuation)
        MaxComplexityByVersion(version) * MaxContinuationSteps
      else
        MaxComplexityByVersion(version)
      _ <- Either.cond(
        maxComplexity._2 <= limit,
        (),
        s"Contract function (${maxComplexity._1}) is too complex: ${maxComplexity._2} > $limit"
      )
      _ <- if (allowContinuation) checkContinuationFirstStep(version, dApp, complexities) else Right(())
    } yield ()

  private def estimateVerifierReduced(
      dApp: DApp,
      complexities: Map[String, Long],
      version: StdLibVersion
  ): Either[String, Unit] =
    dApp.verifierFuncOpt.fold(().asRight[String]) { verifier =>
      val verifierComplexity = complexities(verifier.u.name)
      val limit              = MaxAccountVerifierComplexityByVersion(version)
      Either.cond(
        verifierComplexity <= limit,
        (),
        s"Contract verifier is too complex: $verifierComplexity > $limit"
      )
    }

  def estimateComplexityExact(
      version: StdLibVersion,
      dApp: DApp,
      estimator: ScriptEstimator
  ): Either[String, ((String, Long), Map[String, Long])] =
    for {
      annotatedFunctionComplexities <- estimateAnnotatedFunctions(version, dApp, estimator)
      max = annotatedFunctionComplexities.maximumOption(_._2 compareTo _._2).getOrElse(("", 0L))
    } yield (max, annotatedFunctionComplexities.toMap)

  private def checkContinuationFirstStep(
      version: StdLibVersion,
      dApp: DApp,
      complexities: Map[String, Long]
  ): Either[String, Unit] = {
    val limit = MaxComplexityByVersion(version)
    val multiStepCallables =
      dApp.callableFuncs
        .filter(func => complexities(func.u.name) > limit)
        .map(func => (Some(func.annotation.invocationArgName), func.u))
    for {
      firstStepComplexities <- estimateDeclarations(
        dApp,
        ScriptEstimatorV3.estimateFirstContinuationStep(functionNativeCosts(version).value(), _),
        multiStepCallables
      )
      exceedingFunctions = firstStepComplexities
        .collect { case (functionName, nativeCost) if nativeCost > limit => (functionName, nativeCost) }
        .mkString(", ")
      _ <- Either.cond(
        exceedingFunctions.isEmpty,
        (),
        s"Complexity of state calls exceeding limit = $limit for function(s): $exceedingFunctions"
      )
    } yield ()
  }
}
