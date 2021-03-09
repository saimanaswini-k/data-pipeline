package org.sunbird.job.functions

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.compositesearch.domain.Event
import org.sunbird.job.compositesearch.helpers.{DialCodeMetricIndexerHelper, FailedEventHelper}
import org.sunbird.job.task.SearchIndexerConfig
import org.sunbird.job.util.ElasticSearchUtil
import scala.collection.JavaConverters._

class DialCodeMetricIndexerFunction(config: SearchIndexerConfig,
                                    @transient var elasticUtil: ElasticSearchUtil = null)
  extends BaseProcessFunction[Event, String](config)
    with DialCodeMetricIndexerHelper with FailedEventHelper {

  val mapper: ObjectMapper = new ObjectMapper

  private[this] val logger = LoggerFactory.getLogger(classOf[DialCodeMetricIndexerFunction])

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    elasticUtil = new ElasticSearchUtil(config.esConnectionInfo, config.dialcodeMetricIndex, config.dialcodeMetricIndexType)
    createDialCodeIndex()(elasticUtil)
  }

  override def close(): Unit = {
    elasticUtil.close()
    super.close()
  }

  override def processElement(event: Event, context: ProcessFunction[Event, String]#Context, metrics: Metrics): Unit = {
    metrics.incCounter(config.dialcodeMetricEventCount)
    try {
      val uniqueId = event.readOrDefault("nodeUniqueId", "")
      upsertDialcodeMetricDocument(uniqueId, event.getMap().asScala.toMap)(elasticUtil)
      metrics.incCounter(config.successDialcodeMetricEventCount)
    } catch {
      case ex: Exception =>
        logger.error(s"Error while processing message for identifier : ${event.id}. Error : ", ex)
        metrics.incCounter(config.failedDialcodeMetricEventCount)
        val failedEvent = getFailedEvent(event, ex)
        context.output(config.failedEventOutTag, failedEvent)
        throw ex
    }
  }

  override def metricsList(): List[String] = {
    List(config.successDialcodeMetricEventCount, config.failedDialcodeMetricEventCount, config.dialcodeMetricEventCount)
  }

}