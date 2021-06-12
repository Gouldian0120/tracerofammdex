package org.ergoplatform.dex.executor.amm.repositories

import cats.Monad
import org.ergoplatform.dex.domain.Predicted
import org.ergoplatform.dex.domain.amm.{CfmmPool, PoolId}
import org.ergoplatform.ergo.ErgoNetwork

trait CfmmPools[F[_]] {

  /** Get pool state by pool id.
    */
  def get(id: PoolId): F[Option[CfmmPool]]

  /** Persist predicted pool.
    */
  def put(pool: Predicted[CfmmPool]): F[Unit]
}

object CfmmPools {

  def make[F[_]: Monad](implicit network: ErgoNetwork[F]): CfmmPools[F] =
    new Live[F]

  final class Live[F[_]: Monad](implicit network: ErgoNetwork[F]) extends CfmmPools[F] {

    def get(id: PoolId): F[Option[CfmmPool]] = ???

    def put(pool: Predicted[CfmmPool]): F[Unit] = ???
  }
}
