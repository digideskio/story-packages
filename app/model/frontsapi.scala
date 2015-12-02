package frontsapi.model

import com.gu.facia.client.models.{CollectionJson, ConfigJson, Trail, TrailMetaData}
import com.gu.pandomainauth.model.User
import conf.Configuration
import julienrf.variants.Variants
import org.joda.time.DateTime
import play.api.Logger
import play.api.libs.json._
import services.{ConfigAgent, FrontsApi}
import tools.{FaciaApi, FaciaApiIO}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object CollectionJsonFunctions {

  def sortByGroup(collectionJson: CollectionJson) = collectionJson.copy(
    live = sortTrailsByGroup(collectionJson.live),
    draft = collectionJson.draft.map(sortTrailsByGroup))

  private def sortTrailsByGroup(trails: List[Trail]): List[Trail] = {
    val trailGroups = trails.groupBy(_.meta.flatMap(_.group).map(_.toInt).getOrElse(0))
    trailGroups.keys.toList.sorted(Ordering.Int.reverse).flatMap(trailGroups.getOrElse(_, Nil))
  }

  def updatePreviously(collectionJson: CollectionJson, update: UpdateList): CollectionJson = {
    if (update.live) {
      val itemFromLive: Option[Trail] = collectionJson.live.find(_.id == update.item)
      val updatedPreviously: Option[List[Trail]] =
        (for {
          previousList <- collectionJson.previously
          trail <- itemFromLive
        } yield {
          val previouslyWithoutItem: List[Trail] = previousList.filterNot(_.id == update.item)
          (trail +: previouslyWithoutItem).take(20)
        }).orElse(itemFromLive.map(List.apply(_)))
      collectionJson.copy(previously=updatedPreviously)
    }
    else
      collectionJson
  }

  def updatePreviouslyForPublish(collectionJson: CollectionJson): CollectionJson = {
    val removed: List[Trail] = collectionJson.live.filterNot(t => collectionJson.draft.getOrElse(Nil).exists(_.id == t.id))
    val updatedPreviously = collectionJson.previously
      .map(_.filterNot(t => removed.exists(_.id == t.id)))
      .map(removed ++ _)
      .orElse(Option(removed))
      .map(_.take(20))
    collectionJson.copy(previously=updatedPreviously)
  }
}

sealed trait FaciaToolUpdate

case class UpdateList(
  id: String,
  item: String,
  position: Option[String],
  after: Option[Boolean],
  itemMeta: Option[TrailMetaData],
  live: Boolean,
  draft: Boolean
) extends FaciaToolUpdate

case class Update(update: UpdateList) extends FaciaToolUpdate
case class Remove(remove: UpdateList) extends FaciaToolUpdate

case class UpdateAndRemove(update: UpdateList, remove: UpdateList) extends FaciaToolUpdate

case class DiscardUpdate(id: String) extends FaciaToolUpdate
case class PublishUpdate(id: String) extends FaciaToolUpdate

object UpdateList {
  implicit val format: Format[UpdateList] = Json.format[UpdateList]
}

object FaciaToolUpdate {
  implicit val format: Format[FaciaToolUpdate] = Variants.format[FaciaToolUpdate]((__ \ "type").format[String])
}

case class StreamUpdate(update: FaciaToolUpdate, email: String)

object StreamUpdate {
  implicit val streamUpdateFormat: Format[StreamUpdate] = Json.format[StreamUpdate]
}

trait UpdateActions {

  val collectionCap: Int = Configuration.facia.collectionCap
  implicit val updateListWrite = Json.writes[UpdateList]

  def insertIntoLive(update: UpdateList, identity: User, collectionJson: CollectionJson): CollectionJson =
    if (update.live) {
      val live = updateList(update, identity, collectionJson.live)
      collectionJson.copy(live=live, draft=collectionJson.draft.filter(_ != live))
    }
    else
      collectionJson

  def insertIntoDraft(update: UpdateList, identity: User, collectionJson: CollectionJson): CollectionJson =
    if (update.draft)
      collectionJson.copy(
          draft=collectionJson.draft.map {
            l => updateList(update, identity, l)}.orElse {
              Option(updateList(update, identity, collectionJson.live))
          }.filter(_ != collectionJson.live)
        )
    else
      collectionJson

  def deleteFromLive(update: UpdateList, collectionJson: CollectionJson): CollectionJson =
    if (update.live)
      collectionJson.copy(live=collectionJson.live.filterNot(_.id == update.item))
    else
      collectionJson

  def deleteFromDraft(update: UpdateList, collectionJson: CollectionJson): CollectionJson =
    if (update.draft)
      collectionJson.copy(draft=collectionJson.draft orElse Option(collectionJson.live) map { l => l.filterNot(_.id == update.item) } filter(_ != collectionJson.live) )
    else
      collectionJson

  def putCollectionJson(id: String, collectionJson: CollectionJson): CollectionJson =
    FaciaApiIO.putCollectionJson(id, collectionJson)

  //Archiving
  def archivePublishBlock(collectionId: String, collectionJson: CollectionJson, identity: User): CollectionJson = {
    archiveBlock(collectionId, collectionJson, "publish", identity)
  }

  def archiveDiscardBlock(collectionId: String, collectionJson: CollectionJson, identity: User): CollectionJson = {
    archiveBlock(collectionId, collectionJson, "discard", identity)
  }

  def archiveUpdateBlock(collectionId: String, collectionJson: CollectionJson, updateJson: JsValue, identity: User): CollectionJson = {
    archiveBlock(collectionId, collectionJson, Json.obj("action" -> "update", "update" -> updateJson), identity)
  }

  def archiveDeleteBlock(collectionId: String, collectionJson: CollectionJson, updateJson: JsValue, identity: User): CollectionJson = {
    archiveBlock(collectionId, collectionJson, Json.obj("action" -> "delete", "update" -> updateJson), identity)
  }

  private def archiveBlock(id: String, collectionJson: CollectionJson, action: String, identity: User): CollectionJson =
    archiveBlock(id, collectionJson, Json.obj("action" -> action), identity)

  private def archiveBlock(id: String, collectionJson: CollectionJson, updateJson: JsValue, identity: User): CollectionJson =
    Try(FaciaApiIO.archive(id, collectionJson, updateJson, identity)) match {
      case Failure(t: Throwable) => {
        Logger.warn(t.toString)
        collectionJson
      }
      case Success(_) => collectionJson
    }

  def putMasterConfig(config: ConfigJson, identity: User): Option[ConfigJson] = {
    FaciaApiIO.archiveMasterConfig(config, identity)
    FaciaApiIO.putMasterConfig(config)
  }

  def updateCollectionList(id: String, update: UpdateList, identity: User): Future[Option[CollectionJson]] = {
    lazy val updateJson = Json.toJson(update)
    FrontsApi.amazonClient.collection(id).map { maybeCollectionJson =>
      maybeCollectionJson
        .map(insertIntoLive(update, identity, _))
        .map(insertIntoDraft(update, identity, _))
        .map(removeGroupIfNoLongerGrouped(id, _))
        .map(pruneBlock)
        .map(CollectionJsonFunctions.sortByGroup)
        .map(capCollection)
        .map(FaciaApi.updateIdentity(_, identity))
        .map(putCollectionJson(id, _))
        .map(archiveUpdateBlock(id, _, updateJson, identity))
        .orElse(Option(createCollectionJson(identity, update)))
        .map(putCollectionJson(id, _))}}

  def updateCollectionFilter(id: String, update: UpdateList, identity: User): Future[Option[CollectionJson]] = {
    lazy val updateJson = Json.toJson(update)
    FrontsApi.amazonClient.collection(id).map { maybeCollectionJson =>
      maybeCollectionJson
        .map(CollectionJsonFunctions.updatePreviously(_, update))
        .map(deleteFromLive(update, _))
        .map(deleteFromDraft(update, _))
        .map(removeGroupIfNoLongerGrouped(id, _))
        .map(pruneBlock)
        .map(CollectionJsonFunctions.sortByGroup)
        .map(archiveDeleteBlock(id, _, updateJson, identity))
        .map(FaciaApi.updateIdentity(_, identity))
        .map(putCollectionJson(id, _))}}

  private def updateList(update: UpdateList, identity: User, blocks: List[Trail]): List[Trail] = {
    val trail: Trail = blocks
      .find(_.id == update.item)
      .map { currentTrail =>
        val newMeta = for (updateMeta <- update.itemMeta) yield updateMeta
        currentTrail.copy(meta = newMeta)
      }
      .getOrElse(Trail(update.item, DateTime.now.getMillis, Some(getUserName(identity)), update.itemMeta))

    val listWithoutItem = blocks.filterNot(_.id == update.item)

    val splitList: (List[Trail], List[Trail]) = {
      //Different index logic if item is being place at itself in list
      //(Eg for metadata update, or group change, index must come from list without item removed)
      if (update.position.exists(_ == update.item)) {
        val index = blocks.indexWhere(_.id == update.item)
        listWithoutItem.splitAt(index)
      }
      else {
        val index = update.after.filter {_ == true}
          .map {_ => listWithoutItem.indexWhere(t => update.position.exists(_ == t.id)) + 1}
          .getOrElse { listWithoutItem.indexWhere(t => update.position.exists(_ == t.id)) }
        listWithoutItem.splitAt(index)
      }
    }

    splitList._1 ::: (trail +: splitList._2)
  }

  def createCollectionJson(identity: User, update: UpdateList): CollectionJson = {
    val userName = getUserName(identity)
    if (update.live)
      CollectionJson(List(Trail(update.item, DateTime.now.getMillis, Some(userName), update.itemMeta)), None, None, DateTime.now, userName, identity.email, None, None, None)
    else
      CollectionJson(Nil, Some(List(Trail(update.item, DateTime.now.getMillis, Some(userName), update.itemMeta))), None, DateTime.now, userName, identity.email, None, None, None)
  }

  def capCollection(collectionJson: CollectionJson): CollectionJson =
    collectionJson.copy(live = collectionJson.live.take(collectionCap), draft = collectionJson.draft.map(_.take(collectionCap)))

  def removeGroupIfNoLongerGrouped(collectionId: String, collectionJson: CollectionJson): CollectionJson = {
    ConfigAgent.getConfig(collectionId).flatMap(_.groups) match {
      case Some(groups) if groups.groups.nonEmpty => collectionJson
      case _ => collectionJson.copy(
        live = collectionJson.live.map(removeGroupsFromTrail),
        draft = collectionJson.draft.map(_.map(removeGroupsFromTrail)))
    }
  }

  private def pruneBlock(collectionJson: CollectionJson): CollectionJson =
    collectionJson.copy(
      live = collectionJson.live
        .map(pruneGroupOfZero)
        .map(pruneMetaDataIfEmpty),
      draft = collectionJson.draft.map(
        _.map(pruneGroupOfZero)
         .map(pruneMetaDataIfEmpty)
      )
    )

  private def pruneGroupOfZero(trail: Trail): Trail =
    trail.copy(meta = trail.meta.map(
      metaData => metaData.copy(json = metaData.json.filter{
        case ("group", JsString("0")) => false
        case _ => true})))

  private def pruneMetaDataIfEmpty(trail: Trail): Trail =
    trail.copy(meta = trail.meta.filter(_.json.nonEmpty))

  private def removeGroupsFromTrail(trail: Trail): Trail =
    trail.copy(meta = trail.meta.map(metaData => metaData.copy(json = metaData.json - "group")))

  def createCollectionForTreat(collectionId: String, identity: User, update: UpdateList): CollectionJson = {
    val trail = Trail(update.item, DateTime.now.getMillis, Some(getUserName(identity)), update.itemMeta)
    CollectionJson(
      live          = Nil,
      draft         = None,
      treats        = Option(List(trail)),
      lastUpdated   = DateTime.now,
      updatedBy     = getUserName(identity),
      updatedEmail  = identity.email,
      displayName   = None,
      href          = None,
      previously    = None)}

  def updateTreats(collectionId: String, update: UpdateList, identity: User): Future[Option[CollectionJson]] = {
    lazy val updateJson = Json.toJson(update)
    FaciaApiIO.getCollectionJson(collectionId).map{ maybeCollectionJson =>
      maybeCollectionJson
        .map(updateTreatsList(update, identity, _))
        .map(archiveUpdateBlock(collectionId, _, updateJson, identity))
        .map(FaciaApi.updateIdentity(_, identity))
        .map(putCollectionJson(collectionId, _))
        .orElse(Option(createCollectionForTreat(collectionId, identity, update)))
        .map(putCollectionJson(collectionId, _))}}

  def removeTreats(collectionId: String, update: UpdateList, identity: User): Future[Option[CollectionJson]] = {
    lazy val updateJson = Json.toJson(update)
    FaciaApiIO.getCollectionJson(collectionId).map{ maybeCollectionJson =>
      maybeCollectionJson
        .map(removeFromTreatsList(update, _))
        .map(archiveDeleteBlock(collectionId, _, updateJson, identity))
        .map(FaciaApi.updateIdentity(_, identity))
        .map(putCollectionJson(collectionId, _))}}

  private def updateTreatsList(update: UpdateList, identity: User, collectionJson: CollectionJson): CollectionJson = {
    val updatedTreats = updateList(update, identity, collectionJson.treats.getOrElse(Nil))
    collectionJson.copy(treats = Option(updatedTreats))}

  private def removeFromTreatsList(update: UpdateList, collectionJson: CollectionJson): CollectionJson = {
    val updatedTreats = collectionJson.treats.map(_.filterNot(_.id == update.item))
    collectionJson.copy(treats = updatedTreats)}

  private def getUserName(identity: User): String = s"${identity.firstName} ${identity.lastName}"

}

object UpdateActions extends UpdateActions
