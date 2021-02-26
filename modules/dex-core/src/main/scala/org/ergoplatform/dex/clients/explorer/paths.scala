package org.ergoplatform.dex.clients.explorer

import org.ergoplatform.dex.HexString
import sttp.model.Uri.Segment

object paths {

  val submitTransactionPathSeg: Segment = Segment("api/v0/transactions/send", identity)
  val blocksPathSeg: Segment            = Segment("api/v0/blocks", identity)
  val utxoPathSeg: Segment              = Segment("api/v1/boxes/unspent/byLastEpochs", identity)

  def txsByScriptsPathSeg(templateHash: HexString): Segment =
    Segment(s"api/v1/transactions/byInputsScriptTemplateHash/$templateHash", identity)
}
