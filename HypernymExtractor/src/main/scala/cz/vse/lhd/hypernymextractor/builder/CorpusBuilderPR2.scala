package cz.vse.lhd.hypernymextractor.builder

import java.io.{File, FileOutputStream, PrintWriter}

import com.hp.hpl.jena.query.ARQ
import com.hp.hpl.jena.rdf.model.Statement
import cz.vse.lhd.core.{BasicFunction, NTReader}
import cz.vse.lhd.hypernymextractor.Conf
import gate.creole.SerialAnalyserController
import gate.{Corpus, Factory, ProcessingResource}
import org.slf4j.LoggerFactory

class CorpusBuilderPR2 extends CorpusBuilderPR {

  lazy val logger = LoggerFactory.getLogger(getClass)

  val pipeline = {
    val pl = Factory.createResource("gate.creole.SerialAnalyserController").asInstanceOf[SerialAnalyserController]
    pl.add(Factory.createResource("gate.creole.tokeniser.DefaultTokeniser", Factory.newFeatureMap).asInstanceOf[ProcessingResource])
    pl.add(Factory.createResource("gate.creole.splitter.RegexSentenceSplitter", Factory.newFeatureMap()).asInstanceOf[ProcessingResource])
    pl
  }

  override def execute() = {
    import language.postfixOps
    import scala.collection.JavaConversions._

    ARQ.init()
    /*logger.info("== Gate init ==")
    Gate.init()*/

    logger.info("== Corpus size counting ==")
    val start = getStartPosInArticleNameList.toInt match {
      case x if x <= 0 => 0
      case x => getStartPosInArticleNameList.toInt
    }
    val end = getEndPosInArticleNameList.toInt match {
      case x if x <= 0 => Conf.datasetSize
      case x => getEndPosInArticleNameList.toInt
    }
    val step = 500
    logger.info(s"Start of extraction from $start until $end")
    logger.info("Total steps: " + (end - start))

    BasicFunction.tryClose(new DBpediaLinker(Conf.wikiApi, Conf.lang, Conf.memcachedAddress, Conf.memcachedPort.toInt) with MemCached) { dbpediaLinker =>
      HypernymExtractor(dbpediaLinker, start, end) {
        hypernymExtractor =>
          val disambiguations = getDisambiguations
          val outputFilePath = Conf.outputDir + s"/hypoutpu.$start-$end.log"
          val outputRawWriter = new PrintWriter(new FileOutputStream(outputFilePath + ".raw"))
          val outputResourceWriter = new PrintWriter(new FileOutputStream(outputFilePath + ".dbpedia"))
          implicit val saveHypernym: HypernymExtractor.Hypernym => Unit = {
            hypernym =>
              outputRawWriter.println(hypernym.resourceName + ";" + hypernym.rawHypernym)
              for (resourceHypernym <- hypernym.resourceHypernym)
                outputResourceWriter.println(s"<${hypernym.resourceUri}> <?> <$resourceHypernym>")
          }
          try {
            for (offset <- start until end by step) {
              val endBlock = if (offset + step > end) end else offset + step
              val wikicorpus = Factory.newCorpus("WikipediaCorpus")
              NTReader.fromFile(new File(Conf.datasetShort_abstractsPath)) {
                _.slice(offset, endBlock)
                  .filter(stmt => !disambiguations(stmt.getSubject.getURI))
                  .foldLeft(offset) {
                  (idx, stmt) =>
                    try {
                      addDocToCorpus(wikicorpus, stmt, idx)
                    } catch {
                      case exc: Throwable => logger.error(exc.getMessage, exc)
                    }
                    idx + 1
                }
              }
              if (!wikicorpus.isEmpty) {
                pipeline.setCorpus(wikicorpus)
                pipeline.execute()
                for (doc <- wikicorpus) {
                  try {
                    val sa = doc
                      .getAnnotations.get("Sentence")
                      .minBy(_.getStartNode.getOffset)
                    doc.setContent(doc.getContent.getContent(sa.getStartNode.getOffset, sa.getEndNode.getOffset))
                  } catch {
                    case exc @ (_: gate.util.InvalidOffsetException | _: UnsupportedOperationException) => logger.error(exc.getMessage)
                  }
                }
                hypernymExtractor.extractHypernyms(wikicorpus)
              }
            }
          } finally {
            outputRawWriter.close()
            outputResourceWriter.close()
          }
      }
    }

    logger.info("== Done ==")
  }

  private def normalizeAbstract(str: String) = str.replaceAll("\\(.*?\\)|\\[.*?\\]", "").replaceAll("\\s+", " ")

  private def addDocToCorpus(corpus: Corpus, shortAbstractStmt: Statement, idx: Int): Unit = {
    val doc = Factory.newDocument(normalizeAbstract(shortAbstractStmt.getObject.asLiteral().getString))
    val resourceUri = shortAbstractStmt.getSubject.getURI
    doc.setName("doc-" + idx)
    doc.getFeatures.put("article_title", resourceUri.replaceFirst(s"^${Conf.dbpediaBasicUri}resource/", ""))
    doc.getFeatures.put(
      "wikipedia_url",
      resourceUri
        .replace("dbpedia.org/", "wikipedia.org/")
        .replace("/resource/", "/wiki/"))
    doc.getFeatures.put("dbpedia_url", resourceUri)
    doc.getFeatures.put("lang", Conf.lang)
    corpus.add(doc)
  }

}