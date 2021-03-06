package org.ergoplatform.dex.executor.amm.interpreters

import cats.{Functor, Monad}
import org.ergoplatform.ErgoBox.{NonMandatoryRegisterId, R4}
import org.ergoplatform._
import org.ergoplatform.dex.configs.MonetaryConfig
import org.ergoplatform.dex.domain.amm._
import org.ergoplatform.dex.domain.amm.state.Predicted
import org.ergoplatform.dex.domain.{AssetAmount, BoxInfo, NetworkContext}
import org.ergoplatform.dex.executor.amm.config.ExchangeConfig
import org.ergoplatform.dex.executor.amm.domain.errors.{ExecutionFailed, PriceTooHigh, PriceTooLow}
import org.ergoplatform.dex.executor.amm.interpreters.CFMMInterpreter.CFMMInterpreterTracing
import org.ergoplatform.dex.protocol.amm.AMMContracts
import org.ergoplatform.dex.protocol.amm.AMMType.T2TCFMM
import org.ergoplatform.ergo.syntax._
import org.ergoplatform.ergo.{BoxId, ErgoNetwork, TokenId}
import sigmastate.Values.IntConstant
import sigmastate.eval._
import sigmastate.interpreter.ProverResult
import tofu.logging.Logs
import tofu.syntax.embed._
import tofu.syntax.monadic._
import tofu.syntax.raise._

final class T2TCFMMInterpreter[F[_]: Monad: ExecutionFailed.Raise](
  exchange: ExchangeConfig,
  execution: MonetaryConfig,
  ctx: NetworkContext
)(implicit
  contracts: AMMContracts[T2TCFMM],
  encoder: ErgoAddressEncoder
) extends CFMMInterpreter[T2TCFMM, F] {

  def deposit(deposit: Deposit, pool: CFMMPool): F[(ErgoLikeTransaction, Predicted[CFMMPool])] = {
    val poolBox0   = pool.box
    val depositBox = deposit.box
    val depositIn  = new Input(depositBox.boxId.toErgo, ProverResult.empty)
    val poolIn     = new Input(poolBox0.boxId.toErgo, ProverResult.empty)
    val (inX, inY) = (deposit.params.inX, deposit.params.inY)
    val rewardLP   = pool.rewardLP(inX, inY)
    val poolBox1 = new ErgoBoxCandidate(
      value          = poolBox0.value,
      ergoTree       = contracts.pool,
      creationHeight = ctx.currentHeight,
      additionalTokens = mkPoolTokens(
        pool,
        amountLP = pool.lp.value - rewardLP.value,
        amountX  = pool.x.value + inX.value,
        amountY  = pool.y.value + inY.value
      ),
      additionalRegisters = mkPoolRegs(pool)
    )
    val minerFeeBox = new ErgoBoxCandidate(execution.minerFee, minerFeeProp, ctx.currentHeight)
    val dexFee      = deposit.params.dexFee - execution.minerFee
    val dexFeeBox   = new ErgoBoxCandidate(dexFee, dexFeeProp, ctx.currentHeight)
    val returnBox = new ErgoBoxCandidate(
      value            = depositBox.value - minerFeeBox.value - dexFeeBox.value,
      ergoTree         = deposit.params.p2pk.toErgoTree,
      creationHeight   = ctx.currentHeight,
      additionalTokens = mkTokens(rewardLP.id -> rewardLP.value)
    )
    val inputs      = Vector(poolIn, depositIn)
    val outs        = Vector(poolBox1, returnBox, dexFeeBox, minerFeeBox)
    val tx          = ErgoLikeTransaction(inputs, outs)
    val nextPoolBox = poolBox1.toBox(tx.id, 0)
    val boxInfo     = BoxInfo(BoxId.fromErgo(nextPoolBox.id), nextPoolBox.value, poolBox0.lastConfirmedBoxGix)
    val nextPool    = pool.deposit(inX, inY, boxInfo)
    (tx, nextPool).pure
  }

  def redeem(redeem: Redeem, pool: CFMMPool): F[(ErgoLikeTransaction, Predicted[CFMMPool])] = {
    val poolBox0         = pool.box
    val redeemBox        = redeem.box
    val redeemIn         = new Input(redeemBox.boxId.toErgo, ProverResult.empty)
    val poolIn           = new Input(poolBox0.boxId.toErgo, ProverResult.empty)
    val inLP             = redeem.params.lp
    val (shareX, shareY) = pool.shares(inLP)
    val poolBox1 = new ErgoBoxCandidate(
      value          = poolBox0.value,
      ergoTree       = contracts.pool,
      creationHeight = ctx.currentHeight,
      additionalTokens = mkPoolTokens(
        pool,
        amountLP = pool.lp.value + inLP.value,
        amountX  = pool.x.value - shareX.value,
        amountY  = pool.y.value - shareY.value
      ),
      additionalRegisters = mkPoolRegs(pool)
    )
    val minerFeeBox = new ErgoBoxCandidate(execution.minerFee, minerFeeProp, ctx.currentHeight)
    val dexFee      = redeem.params.dexFee - execution.minerFee
    val dexFeeBox   = new ErgoBoxCandidate(dexFee, dexFeeProp, ctx.currentHeight)
    val returnBox = new ErgoBoxCandidate(
      value          = redeemBox.value - minerFeeBox.value - dexFeeBox.value,
      ergoTree       = redeem.params.p2pk.toErgoTree,
      creationHeight = ctx.currentHeight,
      additionalTokens = mkTokens(
        shareX.id -> shareX.value,
        shareY.id -> shareY.value
      )
    )
    val inputs      = Vector(poolIn, redeemIn)
    val outs        = Vector(poolBox1, returnBox, dexFeeBox, minerFeeBox)
    val tx          = ErgoLikeTransaction(inputs, outs)
    val nextPoolBox = poolBox1.toBox(tx.id, 0)
    val boxInfo     = BoxInfo(BoxId.fromErgo(nextPoolBox.id), nextPoolBox.value, poolBox0.lastConfirmedBoxGix)
    val nextPool    = pool.redeem(inLP, boxInfo)
    (tx, nextPool).pure
  }

  def swap(swap: Swap, pool: CFMMPool): F[(ErgoLikeTransaction, Predicted[CFMMPool])] =
    swapParams(swap, pool).toRaise.map { case (input, output, dexFee) =>
      val poolBox0 = pool.box
      val swapBox  = swap.box
      val swapIn   = new Input(swapBox.boxId.toErgo, ProverResult.empty)
      val poolIn   = new Input(poolBox0.boxId.toErgo, ProverResult.empty)
      val (deltaX, deltaY) =
        if (input.id == pool.x.id) input.value -> -output.value
        else -output.value                     -> input.value
      val poolBox1 = new ErgoBoxCandidate(
        value          = poolBox0.value,
        ergoTree       = contracts.pool,
        creationHeight = ctx.currentHeight,
        additionalTokens = mkPoolTokens(
          pool,
          amountLP = pool.lp.value,
          amountX  = pool.x.value + deltaX,
          amountY  = pool.y.value + deltaY
        ),
        additionalRegisters = mkPoolRegs(pool)
      )
      val minerFeeBox = new ErgoBoxCandidate(execution.minerFee, minerFeeProp, ctx.currentHeight)
      val dexFeeBox   = new ErgoBoxCandidate(dexFee, dexFeeProp, ctx.currentHeight)
      val rewardBox = new ErgoBoxCandidate(
        value            = swapBox.value - minerFeeBox.value - dexFeeBox.value,
        ergoTree         = swap.params.p2pk.toErgoTree,
        creationHeight   = ctx.currentHeight,
        additionalTokens = mkTokens(swap.params.minOutput.id -> output.value)
      )
      val inputs      = Vector(poolIn, swapIn)
      val outs        = Vector(poolBox1, rewardBox, dexFeeBox, minerFeeBox)
      val tx          = ErgoLikeTransaction(inputs, outs)
      val nextPoolBox = poolBox1.toBox(tx.id, 0)
      val boxInfo     = BoxInfo(BoxId.fromErgo(nextPoolBox.id), nextPoolBox.value, poolBox0.lastConfirmedBoxGix)
      val nextPool    = pool.swap(input, boxInfo)
      tx -> nextPool
    }

  private val minerFeeProp = Pay2SAddress(ErgoScriptPredef.feeProposition()).script
  private val dexFeeProp   = exchange.rewardAddress.toErgoTree

  private def swapParams(swap: Swap, pool: CFMMPool): Either[ExecutionFailed, (AssetAmount, AssetAmount, Long)] = {
    val input  = swap.params.input
    val output = pool.outputAmount(input)
    val dexFee = (BigInt(output.value) * swap.params.dexFeePerTokenNum /
      swap.params.dexFeePerTokenDenom - execution.minerFee).toLong
    val maxDexFee = swap.box.value - execution.minerFee - execution.minBoxValue
    if (output < swap.params.minOutput) Left(PriceTooHigh(swap.poolId, swap.params.minOutput, output))
    else if (dexFee > maxDexFee) Left(PriceTooLow(swap.poolId, maxDexFee, dexFee))
    else Right((input, output, dexFee))
  }

  private def mkPoolTokens(pool: CFMMPool, amountLP: Long, amountX: Long, amountY: Long) =
    mkTokens(
      pool.poolId.value -> 1L,
      pool.lp.id        -> amountLP,
      pool.x.id         -> amountX,
      pool.y.id         -> amountY
    )

  private def mkTokens(tokens: (TokenId, Long)*) =
    Colls.fromItems(tokens.map { case (k, v) => k.toErgo -> v }: _*)

  private def mkPoolRegs(pool: CFMMPool) =
    scala.Predef.Map(
      (R4: NonMandatoryRegisterId) -> IntConstant(pool.feeNum)
    )
}

object T2TCFMMInterpreter {

  def make[I[_]: Functor, F[_]: Monad: ExecutionFailed.Raise: ExchangeConfig.Has: MonetaryConfig.Has](implicit
    network: ErgoNetwork[F],
    contracts: AMMContracts[T2TCFMM],
    encoder: ErgoAddressEncoder,
    logs: Logs[I, F]
  ): I[CFMMInterpreter[T2TCFMM, F]] =
    logs.forService[CFMMInterpreter[T2TCFMM, F]].map { implicit l =>
      (
        for {
          exchange   <- ExchangeConfig.access
          execution  <- MonetaryConfig.access
          networkCtx <- NetworkContext.make
        } yield new CFMMInterpreterTracing[T2TCFMM, F] attach new T2TCFMMInterpreter(exchange, execution, networkCtx)
      ).embed
    }
}
