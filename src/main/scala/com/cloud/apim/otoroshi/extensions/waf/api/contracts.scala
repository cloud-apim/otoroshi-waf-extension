package com.cloud.apim.otoroshi.extensions.waf.api

import com.cloud.apim.otoroshi.extensions.waf.entities.ApiContract

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * Every contract, compiled once per version of it (API-1).
 *
 * Compiling a large contract takes a moment, so it is done when the contracts are synchronised,
 * away from any request; a request that finds one not compiled yet compiles it itself. A contract
 * that does not compile stays an error here and checks nothing: a broken contract is reported, it
 * never refuses traffic.
 */
final class ApiContracts {

  private val compiled = new TrieMap[String, (Int, Either[String, CompiledContract])]()

  private def version(contract: ApiContract): Int = (contract.spec, contract.basePath).hashCode

  def get(contract: ApiContract): Either[String, CompiledContract] = {
    val v = version(contract)
    compiled.get(contract.id) match {
      case Some((at, result)) if at == v => result
      case _                             =>
        val result = ContractCompiler.compile(contract.spec, contract.basePath)
        compiled.put(contract.id, (v, result))
        result
    }
  }

  /** Compiles what changed, and forgets what is gone. */
  def warm(contracts: Seq[ApiContract])(using ec: ExecutionContext): Future[Unit] = Future {
    val ids = contracts.map(_.id).toSet
    compiled.filterInPlace((id, _) => ids.contains(id))
    contracts.filter(_.enabled).foreach(get)
  }
}
