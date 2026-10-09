package com.cloud.apim.otoroshi.extensions.waf.studio

import otoroshi.api.{Resource, WriteAction}
import otoroshi.env.Env
import otoroshi.events.{AdminApiEvent, Audit}
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.*

import scala.concurrent.{ExecutionContext, Future}

/**
 * One kind of entity, read and written through the resource access of the admin api, with the rights
 * of the caller: the same json format, the same validation, the same audit event as the generic
 * admin api, and what the caller cannot read does not exist for it.
 */
final class StudioEntities(val group: String, val plural: String)(using env: Env, ec: ExecutionContext) {

  import ThreatStudioApiError.*

  private def lookup: Option[Resource] = env.allResources.resources.find(r => r.group == group && r.pluralName == plural)

  private def resource: Resource =
    lookup.getOrElse(throw ThreatStudioApiError(500, "internal_error", s"the resource $group/$plural is not available"))

  def singular: String = resource.singularName

  def idOf(entity: JsValue): String = entity.select(resource.access.idFieldName()).asOptString.getOrElse("")

  /** Every entity, readable or not: only for what has to be computed over all of them. */
  def everything(): Future[Seq[JsObject]] = lookup match {
    case None    => Seq.empty[JsObject].vfuture
    case Some(r) => r.access.findAll(r.version.name).map(_.collect { case o: JsObject => o })
  }

  // datastore reads rather than the in-memory state, so what was just written is what comes back
  def all()(using call: ThreatStudioApiRequest): Future[Seq[JsObject]] =
    everything().map(_.filter(e => call.canUserReadJson(e)))

  def get(id: String)(using call: ThreatStudioApiRequest): Future[Option[JsObject]] = {
    val r = resource
    r.access.findOne(r.version.name, id).map(_.collect { case o: JsObject if call.canUserReadJson(o) => o })
  }

  def template(params: Map[String, String] = Map.empty): JsObject = {
    val r = resource
    r.access.template(r.version.name, params, None).asOpt[JsObject].getOrElse(Json.obj())
  }

  def create(entity: JsObject)(using call: ThreatStudioApiRequest): Future[JsObject] = write(entity, WriteAction.Create)

  def update(entity: JsObject)(using call: ThreatStudioApiRequest): Future[JsObject] = write(entity, WriteAction.Update)

  private def write(entity: JsObject, action: WriteAction)(using call: ThreatStudioApiRequest): Future[JsObject] = {
    val r       = resource
    val version = r.version.name
    val id      = idOf(entity)
    r.access.findOne(version, id).flatMap { old =>
      (action, old) match {
        case (WriteAction.Create, Some(_))                              => Future.failed(conflict(s"${r.singularName} '$id' already exists"))
        case (WriteAction.Update, None)                                 => Future.failed(notFound(s"${r.singularName} '$id' not found"))
        case (WriteAction.Update, Some(o)) if !call.canUserReadJson(o) => Future.failed(notFound(s"${r.singularName} '$id' not found"))
        case _ if !call.canUserWriteJson(entity) || old.exists(o => !call.canUserWriteJson(o)) =>
          Future.failed(forbidden(s"you cannot write the ${r.singularName} '$id'"))
        case _                                                          =>
          r.access.validateToJson(entity, r.singularName, Right(None)) match {
            case JsError(errors) =>
              Future.failed(
                badRequest(
                  s"invalid ${r.singularName}: ${errors.map { case (path, errs) => s"$path ${errs.flatMap(_.messages).mkString(", ")}" }.mkString(", ")}"
                )
              )
            case JsSuccess(_, _) =>
              val update = action == WriteAction.Update
              r.access.create(version, r.singularName, if (update) id.some else None, entity, action, old).flatMap {
                case Left(err)    => Future.failed(badRequest(s"invalid ${r.singularName}: ${err.stringify}"))
                case Right(saved) =>
                  audit(
                    s"${if (update) "UPDATE" else "CREATE"}_${r.singularName.toUpperCase}",
                    s"Threat Studio api ${if (update) "updated" else "created"} a ${r.singularName}",
                    entity
                  )
                  saved.asObject.vfuture
              }
          }
      }
    }
  }

  def delete(id: String)(using call: ThreatStudioApiRequest): Future[Unit] = {
    val r = resource
    r.access.findOne(r.version.name, id).flatMap {
      case Some(o) if !call.canUserReadJson(o)  => Future.failed(notFound(s"${r.singularName} '$id' not found"))
      case Some(o) if !call.canUserWriteJson(o) => Future.failed(forbidden(s"you cannot delete the ${r.singularName} '$id'"))
      case _                                    =>
        r.access.deleteOne(r.version.name, id, r.singularName).flatMap {
          case Left(err) => Future.failed(badRequest(s"unable to delete ${r.singularName} '$id': ${err.stringify}"))
          case Right(_)  =>
            audit(s"DELETE_${r.singularName.toUpperCase}", s"Threat Studio api deleted a ${r.singularName}", Json.obj("id" -> id))
            ().vfuture
        }
    }
  }

  private def audit(action: String, message: String, meta: JsValue)(using call: ThreatStudioApiRequest): Unit =
    Audit.send(
      AdminApiEvent(
        env.snowflakeGenerator.nextIdStr(),
        env.env,
        Some(call.apikey),
        // the backoffice user, for a call relayed by the backoffice
        call.user,
        action,
        message,
        call.req.theIpAddress,
        call.req.theUserAgent,
        Json.obj("entity" -> meta, "actor" -> Json.toJson(call.actor.map(_.json)))
      )
    )
}
