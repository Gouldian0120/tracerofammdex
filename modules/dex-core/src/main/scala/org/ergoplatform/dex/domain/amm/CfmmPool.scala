package org.ergoplatform.dex.domain.amm

import derevo.cats.show
import derevo.circe.{decoder, encoder}
import derevo.derive
import org.ergoplatform.dex.domain.amm.state.Predicted
import org.ergoplatform.dex.domain.{AssetAmount, BoxInfo}
import org.ergoplatform.dex.protocol.amm.constants
import scodec.Codec
import scodec.codecs._
import sttp.tapir.{Schema, Validator}
import tofu.logging.derivation.loggable

@derive(encoder, decoder, loggable, show)
final case class CFMMPool(
  poolId: PoolId,
  lp: AssetAmount,
  x: AssetAmount,
  y: AssetAmount,
  feeNum: Int,
  box: BoxInfo
) {

  def supplyLP: Long = constants.cfmm.TotalEmissionLP - lp.value

  def deposit(inX: AssetAmount, inY: AssetAmount, nextBox: BoxInfo): Predicted[CFMMPool] = {
    val unlocked = math.min(
      (BigInt(inX.value) * supplyLP / x.value).toLong,
      (BigInt(inY.value) * supplyLP / y.value).toLong
    )
    Predicted(copy(lp = lp - unlocked, x = x + inX, y = y + inY, box = nextBox))
  }

  def redeem(inLp: AssetAmount, nextBox: BoxInfo): Predicted[CFMMPool] = {
    val redeemedX = (BigInt(inLp.value) * x.value / supplyLP).toLong
    val redeemedY = (BigInt(inLp.value) * y.value / supplyLP).toLong
    Predicted(copy(lp = lp + inLp, x = x - redeemedX, y = y - redeemedY, box = nextBox))
  }

  def swap(in: AssetAmount, nextBox: BoxInfo): Predicted[CFMMPool] = {
    val (deltaX, deltaY) =
      if (in.id == x.id)
        (
          BigInt(in.value),
          -BigInt(y.value) * in.value * feeNum /
          (BigInt(x.value) * constants.cfmm.FeeDenominator + BigInt(in.value) * feeNum)
        )
      else
        (
          -BigInt(x.value) * in.value * feeNum /
          (BigInt(y.value) * constants.cfmm.FeeDenominator + BigInt(in.value) * feeNum),
          BigInt(in.value)
        )
    Predicted(copy(x = x + deltaX.toLong, y = y + deltaY.toLong, box = nextBox))
  }

  def rewardLP(inX: AssetAmount, inY: AssetAmount): AssetAmount =
    lp.withAmount(
      math.min(
        (BigInt(inX.value) * supplyLP / x.value).toLong,
        (BigInt(inY.value) * supplyLP / y.value).toLong
      )
    )

  def shares(lpIn: AssetAmount): (AssetAmount, AssetAmount) =
    x.withAmount(BigInt(lpIn.value) * x.value / supplyLP) ->
    y.withAmount(BigInt(lpIn.value) * y.value / supplyLP)

  def outputAmount(input: AssetAmount): AssetAmount = {
    def out(in: AssetAmount, out: AssetAmount) =
      out.withAmount(
        BigInt(out.value) * input.value * feeNum /
        (BigInt(in.value) * constants.cfmm.FeeDenominator + BigInt(input.value) * feeNum)
      )
    if (input.id == x.id) out(x, y) else out(y, x)
  }
}

object CFMMPool {
  implicit val schema: Schema[CFMMPool]       = Schema.derived[CFMMPool]
  implicit val validator: Validator[CFMMPool] = schema.validator

  implicit val codec: Codec[CFMMPool] =
    (
      implicitly[Codec[PoolId]] ::
        implicitly[Codec[AssetAmount]] ::
        implicitly[Codec[AssetAmount]] ::
        implicitly[Codec[AssetAmount]] ::
        int32 ::
        implicitly[Codec[BoxInfo]]
    ).as[CFMMPool]
}
