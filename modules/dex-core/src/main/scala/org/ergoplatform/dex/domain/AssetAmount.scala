package org.ergoplatform.dex.domain

import derevo.cats.show
import derevo.circe.{decoder, encoder}
import derevo.derive
import org.ergoplatform.ergo.TokenId
import org.ergoplatform.ergo.models.BoxAsset
import sttp.tapir.{Schema, Validator}
import tofu.logging.derivation.loggable

@derive(show, encoder, decoder, loggable)
final case class AssetAmount(id: TokenId, value: Long, ticker: Option[String]) {

  def >=(that: AssetAmount): Boolean = value >= that.value

  def withAmount(x: Long): AssetAmount = copy(value = x)

  def withAmount(x: BigInt): AssetAmount = copy(value = x.toLong)

  def -(that: Long): AssetAmount = withAmount(value - that)
  def +(that: Long): AssetAmount = withAmount(value + that)

  def -(that: AssetAmount): AssetAmount = withAmount(value - that.value)
  def +(that: AssetAmount): AssetAmount = withAmount(value + that.value)
}

object AssetAmount {

  def fromBoxAsset(boxAsset: BoxAsset): AssetAmount =
    AssetAmount(boxAsset.tokenId, boxAsset.amount, boxAsset.name)

  implicit val schema: Schema[AssetAmount]       = Schema.derived[AssetAmount]
  implicit val validator: Validator[AssetAmount] = schema.validator
}
