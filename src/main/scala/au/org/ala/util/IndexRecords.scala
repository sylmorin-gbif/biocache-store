
package au.org.ala.util
import java.util.ArrayList
import org.slf4j.LoggerFactory
import au.org.ala.biocache._
import java.io.File
import java.util.Date
import scala.collection.mutable.HashMap

/**
 * Index the Cassandra Records to conform to the fields
 * as defined in the schema.xml file.
 *
 * @author Natasha Carter
 */
object IndexRecords {

  import FileHelper._

  val logger = LoggerFactory.getLogger("IndexRecords")
  val indexer = Config.getInstance(classOf[IndexDAO]).asInstanceOf[IndexDAO]
  val occurrenceDAO = Config.getInstance(classOf[OccurrenceDAO]).asInstanceOf[OccurrenceDAO]
  val persistenceManager = Config.getInstance(classOf[PersistenceManager]).asInstanceOf[PersistenceManager]

  def main(args: Array[String]): Unit = {

    var startUuid:Option[String] = None

    var dataResource:Option[String] = None
    var empty:Boolean =false
    var check:Boolean=false
    var startDate:Option[String]=None
    val parser = new OptionParser("index records options") {
        opt("empty", "empty the index first", {empty=true})
        opt("check","check to see if the record is deleted before indexing",{check=true})
        opt("s", "start","The record to start with", {v:String => startUuid = Some(v)})
        opt("dr", "resource", "The data resource to process", {v:String =>dataResource = Some(v)})
        opt("date", "date", "The earliest modification date for records to be indexed. Date in the form yyyy-mm-dd",{v:String => startDate = Some(v)})
    }

    if(parser.parse(args)){
        //delete the content of the index
        if(empty){
           logger.info("Emptying index")
           indexer.emptyIndex
        }        
        index(startUuid, dataResource, false, false, startDate, check)
     }
  }

  def index(startUuid:Option[String], dataResource:Option[String], optimise:Boolean = false, shutdown:Boolean = false,
            startDate:Option[String]=None, checkDeleted:Boolean=false) = {

    val startKey = {
        if(startUuid.isEmpty && !dataResource.isEmpty) {
          dataResource.get +"|"
        } else {
            startUuid.get
        }
    }

    var date:Option[Date]=None
    if(!startDate.isEmpty){
        date = DateParser.parseStringToDate(startDate.get +" 00:00:00")
        if(date.isEmpty)
            throw new Exception("Date is in incorrect format. Try yyyy-mm-dd")
        logger.info("Indexing will be restricted to records changed after " + date.get)
    }

    val endKey = if(dataResource.isEmpty) "" else dataResource.get +"|~"
    logger.info("Starting to index " + startKey + " until " + endKey)
    indexRange(startKey, endKey, date, checkDeleted)
    //index any remaining items before exiting
    indexer.finaliseIndex(optimise, shutdown)
  }


  def indexRange(startUuid:String, endUuid:String, startDate:Option[Date]=None, checkDeleted:Boolean=false)={
    var counter = 0
    val start = System.currentTimeMillis
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    performPaging( (guid, map) => {
        counter += 1
        val fullMap = new HashMap[String, String]
        fullMap ++= map
        ///convert EL and CL properties at this stage
        fullMap ++= Json.toStringMap(map.getOrElse("el.p", "{}"))
        fullMap ++= Json.toStringMap(map.getOrElse("cl.p", "{}"))
        val mapToIndex = fullMap.toMap

        indexer.indexFromMap(guid, mapToIndex, startDate=startDate)
        if (counter % 1000 == 0) {
          finishTime = System.currentTimeMillis
          logger.info(counter + " >> Last key : " + guid + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
          startTime = System.currentTimeMillis
        }
        true
    }, startUuid, endUuid, checkDeleted = checkDeleted)

    finishTime = System.currentTimeMillis
    logger.info("Total indexing time " + ((finishTime-start).toFloat)/1000f + " seconds")
  }

  /**
   * Page over records function
   */
  def performPaging(proc:((String, Map[String,String])=>Boolean),startKey:String="", endKey:String="", pageSize: Int = 1000, checkDeleted:Boolean=false){
      if(checkDeleted){
          persistenceManager.pageOverSelect("occ",(guid, map)=>{
              if(map.getOrElse(FullRecordMapper.deletedColumn, "false").equals("false")){
                  val map = persistenceManager.get(guid, "occ")
                  if(!map.isEmpty){
                      proc(guid, map.get)
                  }
              }
              true
              },startKey,endKey,pageSize,"uuid", "rowKey",FullRecordMapper.deletedColumn)
      } else {
          persistenceManager.pageOverAll("occ", (guid, map) => { proc(guid, map) },startKey,endKey)
      }
  }

  /**
   * Indexes the supplied list of rowKeys
   */
  def indexList(file:File)={
      var counter = 0
      var startTime = System.currentTimeMillis
      var finishTime = System.currentTimeMillis
      
      file.foreachLine(line=>{
          counter+=1
          val map =persistenceManager.get(line,"occ")
          if(!map.isEmpty) indexer.indexFromMap(line, map.get)

          if (counter % 1000 == 0) {
            finishTime = System.currentTimeMillis
            logger.info(counter + " >> Last key : " + line + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
            startTime = System.currentTimeMillis
          }
      })
  
      indexer.finaliseIndex(false, true)
  }
  

  def processFullRecords(){

    var counter = 0
    var startTime = System.currentTimeMillis
    var finishTime = System.currentTimeMillis
    var items = new ArrayList[OccurrenceIndex]()

     //page over all records and process
    occurrenceDAO.pageOverAllVersions(versions => {
      counter += 1
      if (!versions.isEmpty) {
        val v = versions.get

        val (raw,processed) = (v(0),v(1))

        items.add(indexer.getOccIndexModel(raw,processed).get)
        //debug counter
        if (counter % 1000 == 0) {
          //add the items to the configured indexer
          indexer.index(items);
          items.removeAll(items);
          finishTime = System.currentTimeMillis
          logger.info(counter + " >> Last key : " + v(0).uuid + ", records per sec: " + 1000f / (((finishTime - startTime).toFloat) / 1000f))
          startTime = System.currentTimeMillis
        }
      }
      true
    })
  }
}