/**
 * Copyright 2011 Pablo Mendes, Max Jakob
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dbpedia.spotlight.lucene.index

import org.dbpedia.spotlight.lucene.LuceneManager
import org.apache.lucene.store.FSDirectory
import scala.collection.JavaConversions._
import org.dbpedia.spotlight.model.SurfaceForm
import org.dbpedia.spotlight.util.{IndexingConfiguration, ExtractCandidateMap}
import java.util.Scanner
import java.io.{FileInputStream, File}
import org.apache.commons.logging.{LogFactory, Log}
import javax.xml.crypto.dsig.Transform

/**
 * In our first implementation we used to index all anchor text found in Wikipedia as surface forms to a target URI.
 * We have since moved to indexing without surface forms, then pre-processing the anchors before indexing.
 * Some possible pre-processing steps: eliminating low count surface forms, adding acronyms, name variations, etc.
 * TODO think of some stemming to provide more alternative matches for a sf (e.g. http://www.mpi-inf.mpg.de/yago-naga/javatools/doc/javatools/parsers/PlingStemmer.html)
 *
 * @author maxjakob
 * @author pablomendes (alternative surface forms)
 */

object AddSurfaceFormsToIndex
{
    val LOG: Log = LogFactory.getLog(this.getClass)

    def toLowercase(sf: String, lowerCased: Boolean) : List[String] = {
        if (lowerCased) (sf.toLowerCase :: List(sf)) else List(sf)
    }

    def fromTitlesToAlternatives(sf: String) : List[String] = { //TODO move to an analyzer
        val alternatives = new java.util.HashSet[String]()
        alternatives.add(sf)
        alternatives.add(sf.toLowerCase)
        if (sf.toLowerCase.startsWith("the ")) {
            alternatives.add(sf.substring(3).trim())
            alternatives.add(sf.toLowerCase.replace("the ",""))
        }
        if (sf.toLowerCase.startsWith("a ")) {
            alternatives.add(sf.substring(1).trim())
            alternatives.add(sf.toLowerCase.replace("a ",""))
        }
        if (sf.toLowerCase.startsWith("an ")) {
            alternatives.add(sf.substring(2).trim())
            alternatives.add(sf.toLowerCase.replace("an ",""))
        }
        if (sf.toLowerCase.endsWith("s")) {
            alternatives.add(sf.substring(0,sf.length()-1).trim())
        }
        alternatives.add(sf.replaceAll("[^A-Za-z0-9 ]", " ").trim())
        alternatives.toList
    }

    // map from URI to list of surface forms
    // used by IndexEnricher
    // uri -> list(sf1, sf2)
    def loadSurfaceForms(surfaceFormsFileName: String, transform : String => List[String]) = {

        LOG.info("Getting surface form map...")
        val reverseMap : java.util.Map[String, java.util.LinkedHashSet[SurfaceForm]] = new java.util.HashMap[String, java.util.LinkedHashSet[SurfaceForm]]()
        val separator = "\t"
        val tsvScanner = new Scanner(new FileInputStream(surfaceFormsFileName), "UTF-8")
        while (tsvScanner.hasNextLine) {
            val line = tsvScanner.nextLine.split(separator)
            val sfAlternatives = transform(line(0)).map(sf => new SurfaceForm(sf))
            val uri = line(1)
            var sfSet = reverseMap.get(uri)
            if (sfSet == null) {
                sfSet = new java.util.LinkedHashSet[SurfaceForm]()
            }
            sfSet.addAll(sfAlternatives)
            reverseMap.put(uri, sfSet)
        }
        LOG.info("Done.")
        reverseMap

    }

    def main(args : Array[String]) {
        val indexingConfigFileName = args(0)
        val lowerCased : Boolean = if (args.size>1) args(1).toLowerCase().contains("lowercase") else false
        val alternatives = if (args.size>1) args(1).toLowerCase().contains("alternative") else false

        println("alternatives is %s".format(alternatives.toString))

        val config = new IndexingConfiguration(indexingConfigFileName)
        val indexFileName = config.get("org.dbpedia.spotlight.index.dir")
        val surfaceFormsFileName = config.get("org.dbpedia.spotlight.data.surfaceForms")



        val indexFile = new File(indexFileName)
        if (!indexFile.exists)
            throw new IllegalArgumentException("index dir "+indexFile+" does not exist; can't add surface forms")
        val luceneManager = new LuceneManager.BufferedMerging(FSDirectory.open(indexFile))

        val sfIndexer = new IndexEnricher(luceneManager)

        //val sfMap = loadSurfaceForms(surfaceFormsFileName, if (alternatives) fromTitlesToAlternatives(_) else toLowercase(_,lowerCased))
        val sfMap = loadSurfaceForms(surfaceFormsFileName, fromTitlesToAlternatives)
        sfIndexer.enrichWithSurfaceForms(sfMap)
        sfIndexer.close
    }

}