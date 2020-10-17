package org.ergoplatform.dex.watcher.modules

import cats.{Foldable, Functor, FunctorFilter, Monad}
import derevo.derive
import mouse.any._
import org.ergoplatform.dex.context.HasCommitPolicy
import org.ergoplatform.dex.protocol.models.Output
import org.ergoplatform.dex.streaming.CommitPolicy
import org.ergoplatform.dex.streaming.syntax._
import org.ergoplatform.dex.watcher.Orders
import org.ergoplatform.dex.watcher.streaming.StreamingBundle
import tofu.higherKind.derived.representableK
import tofu.logging._
import tofu.streams.{Evals, Temporal}
import tofu.syntax.context._
import tofu.syntax.embed._
import tofu.syntax.monadic._
import tofu.syntax.streams.emits._
import tofu.syntax.streams.evals._
import tofu.syntax.streams.filter._

@derive(representableK)
trait OrdersWatcher[F[_]] {

  def run: F[Unit]
}

object OrdersWatcher {

  def make[
    I[_]: Functor,
    F[_]: Monad: Evals[*[_], G]: Temporal[*[_], C]: FunctorFilter: HasCommitPolicy,
    G[_]: Monad,
    C[_]: Foldable
  ](implicit
    streaming: StreamingBundle[F, G],
    logs: Logs[I, G],
    orders: Orders[G]
  ): I[OrdersWatcher[F]] =
    logs.forService[OrdersWatcher[F]].map { implicit l =>
      (context[F] map (policy => new Live[F, G, C](policy): OrdersWatcher[F])).embed
    }

  final private class Live[
    F[_]: Monad: Evals[*[_], G]: Temporal[*[_], C]: FunctorFilter,
    G[_]: Monad: Logging,
    C[_]: Foldable
  ](commitPolicy: CommitPolicy)(implicit streaming: StreamingBundle[F, G], orders: Orders[G])
    extends OrdersWatcher[F] {

    def run: F[Unit] =
      streaming.consumer.stream
        .flatTap(rec => process(rec.message.outputs))
        .commitBatchWithin[C](commitPolicy.maxBatchSize, commitPolicy.commitTimeout)

    private def process(outputs: List[Output]): F[Unit] =
      emits(outputs).evalMap(orders.makeOrder).unNone.thrush(streaming.producer.produce)
  }
}
