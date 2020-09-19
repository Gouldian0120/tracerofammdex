package org.ergoplatform.dex.matcher.services

import cats.{FlatMap, Functor, Monad}
import mouse.anyf._
import org.ergoplatform.dex.PairId
import org.ergoplatform.dex.domain.models.Trade.AnyTrade
import org.ergoplatform.dex.domain.models.Order._
import org.ergoplatform.dex.domain.syntax.trade._
import org.ergoplatform.dex.domain.syntax.order._
import org.ergoplatform.dex.matcher.repositories.OrdersRepo
import tofu.doobie.transactor.Txr
import tofu.logging.{Logging, Logs}
import tofu.syntax.monadic._
import tofu.syntax.logging._

import scala.annotation.tailrec
import scala.language.existentials

final class LimitOrderBook[F[_]: FlatMap: Logging, D[_]: Monad](
  repo: OrdersRepo[D],
  txr: Txr.Aux[F, D]
) extends OrderBook[F] {

  import LimitOrderBook._

  def process(pairId: PairId)(orders: List[AnyOrder]): F[List[AnyTrade]] = {
    val (sell, buy)                 = orders.partitioned
    val List(sellDemand, buyDemand) = List(sell, buy).map(_.map(_.amount).sum)
    info"Processing [${orders.size}] new orders of pair [$pairId]" >>
    (repo.getSellWall(pairId, buyDemand), repo.getBuyWall(pairId, sellDemand))
      .mapN((oldSell, oldBuy) => mkMatch(oldSell ++ sell, oldBuy ++ buy))
      .flatTap { matches =>
        val matched   = matches.flatMap(_.orders.map(_.id).toList)
        val unmatched = orders.filterNot(o => matched.contains(o.id))
        repo.remove(matched) >> repo.insert(unmatched)
      }
      .thrushK(txr.trans)
  }
}

object LimitOrderBook {

  implicit private val sellOrd: Ordering[Ask] = Ordering.by(o => (o.price, -o.fee))
  implicit private val buyOrd: Ordering[Bid]  = Ordering.by(o => (-o.price, -o.fee))

  def make[I[_]: Functor, F[_]: FlatMap, D[_]: Monad](
    repo: OrdersRepo[D],
    txr: Txr.Aux[F, D]
  )(implicit logs: Logs[I, F]): I[LimitOrderBook[F, D]] =
    logs.forService[LimitOrderBook[F, D]] map { implicit l =>
      new LimitOrderBook[F, D](repo, txr)
    }

  private[services] def mkMatch(
    sellOrders: List[Ask],
    buyOrders: List[Bid]
  ): List[AnyTrade] = {
    @tailrec def matchLoop(
      sellOrders: List[Ask],
      buyOrders: List[Bid],
      matched: List[AnyTrade]
    ): List[AnyTrade] =
      (sellOrders.headOption, buyOrders.headOption) match {
        case (Some(sell), Some(buy)) if sell.price <= buy.price =>
          if (sell.fee > buy.fee)
            sell fillWith buyOrders match {
              case Some(anyMatch) =>
                matchLoop(
                  sellOrders.tail,
                  buyOrders.dropWhile(
                    anyMatch.counterOrders.toList.contains
                  ), // todo: make sure orders compared correctly
                  anyMatch +: matched
                )
              case None =>
                matched
            }
          else
            buy fillWith sellOrders match {
              case Some(anyMatch) =>
                matchLoop(
                  sellOrders.dropWhile(anyMatch.counterOrders.toList.contains),
                  buyOrders.tail,
                  anyMatch +: matched
                )
              case None =>
                matched
            }
        case _ => matched
      }
    matchLoop(sellOrders.sorted, buyOrders.sorted, Nil)
  }
}
