/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark_ml.sequoia_forest

import scopt.OptionParser
import org.apache.spark.{ SparkContext, SparkConf }

/**
 * Config object to be set by command line arguments.
 */
case class PredictorConfig(
  inputPath: String = null,
  outputPath: String = null,
  forestPath: String = null,
  delimiter: String = "\t",
  labelIndex: Int = -1,
  outputFieldIndices: Set[Int] = Set[Int](0),
  indicesToIgnore: Set[Int] = Set[Int](),
  pauseDuration: Int = 10)

/**
 * Read a stored Sequoia Forest and predict on a new data set in HDFS.
 */
object SequoiaForestPredictor {
  def main(args: Array[String]) {
    val defaultConfig = PredictorConfig()

    // Command line argument parser.
    val parser = new OptionParser[PredictorConfig]("SequoiaForestPredictor") {
      head("SequoiaForestPredictor: Use a previously trained Sequoia Forest to predict on a new data set.")
      opt[String]("inputPath")
        .text("Path to delimited text file(s) (e.g. csv/tsv) that the forest will predict on. The features should be in the same order as the training data.")
        .required()
        .action((x, c) => c.copy(inputPath = x))
      opt[String]("outputPath")
        .text("Output path (directory) where predictions for input will be written. Chosen fields will also be written together with predictions.")
        .required()
        .action((x, c) => c.copy(outputPath = x))
      opt[String]("forestPath")
        .text("HDFS path where the trained forest is stored.")
        .required()
        .action((x, c) => c.copy(forestPath = x))
      opt[String]("delimiter")
        .text("Delimiter string for input/output data. The default is \"\\t\"")
        .action((x, c) => c.copy(delimiter = x))
      opt[Int]("labelIndex")
        .text("If the data set contains a label, this should be set to the label index for validations. The default is -1, meaning that there's no label.")
        .action((x, c) => c.copy(labelIndex = x))
      opt[String]("outputFieldIndices")
        .text("A comma separated indices of fields that you want to include with the prediction outputs. This is useful for identifying rows. The default output field index is 0 (first column).")
        .action((x, c) => c.copy(outputFieldIndices = x.split(",").map(value => value.toInt).toSet))
      opt[String]("indicesToIgnore")
        .text("A comma separated indices of columns to be ignored (not used as features). The number of used features should be the same as the number of features used for training and should be in the same order as the training set. The default is empty (no columns are ignored).")
        .action((x, c) => c.copy(indicesToIgnore = x.split(",").map(value => value.toInt).toSet))
      opt[Int]("pauseDuration")
        .text("Time to pause after finished with predictions in seconds. This is useful for some Yarn clusters where the log messages are not stored after jobs are finished. The default is 10 seconds.")
        .action((x, c) => c.copy(pauseDuration = x))
      checkConfig(config =>
        if (config.outputFieldIndices.foldLeft(false)((invalid, value) => value < 0 || invalid)) failure("The output field indices contain invalid values.")
        else if (config.indicesToIgnore.foldLeft(false)((invalid, value) => value < 0 || invalid)) failure("The ignored column indices contain invalid values.")
        else if (config.pauseDuration < 0) failure("The pause duration should not be negative.")
        else success)
    }

    // Parse the argument and then run.
    parser.parse(args, defaultConfig).map { config =>
      run(config)
    }.getOrElse {
      Thread.sleep(10000) // Sleep for 10 seconds so that people may see error messages in Yarn clusters where logs are not stored.
      sys.exit(1)
    }
  }

  /**
   * Run prediction.
   * @param config Configuration for the predictor.
   */
  def run(config: PredictorConfig): Unit = {
    val conf = new SparkConf().setAppName("SequoiaForestPredictor")
    val sc = new SparkContext(conf)

    try {
      val indicesToIgnore = config.indicesToIgnore
      val outputIndices = config.outputFieldIndices
      val labelIndex = config.labelIndex
      val delimiter = config.delimiter
      val outputPath = config.outputPath

      val forest: SequoiaForest = SequoiaForestReader.readForest(config.forestPath, sc.hadoopConfiguration)
      val treeType = forest.treeType
      val broadcastForest = sc.broadcast(forest)

      val predictor = (line: String) => {
        val elems = line.split(delimiter)
        val numUsedColumns = elems.length - indicesToIgnore.size
        val numFeatures = if (labelIndex >= 0) numUsedColumns - 1 else numUsedColumns
        val features = new Array[Double](numFeatures)
        val outputFields = new Array[String](outputIndices.size)

        var col = 0
        var featId = 0
        var outputId = 0
        while (col < elems.length) {
          if (col != labelIndex && !indicesToIgnore.contains(col)) {
            // Feature indices get shifted after subtracting the label index and the ignored columns.
            features(featId) = elems(col).toDouble
            featId += 1
          }

          if (outputIndices.contains(col)) {
            outputFields(outputId) = elems(col)
            outputId += 1
          }

          col += 1
        }

        val prediction = broadcastForest.value.predict(features)

        (features, outputFields, prediction)
      }

      val labelParser = (line: String) => {
        val elems = line.split(delimiter)
        elems(labelIndex).toDouble
      }

      val rawRDD = sc.textFile(config.inputPath)
      val predictionRDD = rawRDD.map(predictor)

      // Predict and save the results in the output path.
      predictionRDD.map(row => {
        val outputFields = row._2
        val prediction = row._3
        outputFields.foldLeft("")((curStr, str) => curStr + str + delimiter) + prediction
      }).saveAsTextFile(outputPath)

      // If there's the label index, compare prediction
      if (labelIndex >= 0) {
        val labelRDD = rawRDD.map(labelParser)
        val validationStats = predictionRDD.zip(labelRDD).map(row => {
          val prediction = row._1._3
          val label = row._2
          if (treeType == TreeType.Classification_InfoGain) {
            if (label == prediction) (1.0, 1.0) else (0.0, 1.0)
          } else {
            val error = label - prediction
            (error * error, 1.0)
          }
        }).reduce((a, b) => {
          if (treeType == TreeType.Classification_InfoGain) {
            (a._1 + b._1, a._2 + b._2)
          } else {
            val totalCount = a._2 + b._2
            val w1 = a._2 / totalCount
            val w2 = b._2 / totalCount
            (a._1 * w1 + b._1 * w2, totalCount)
          }
        })

        // Print validation results.
        if (treeType == TreeType.Classification_InfoGain) {
          println("Accuracy for classification:")
          println("Num Correct : " + validationStats._1.toInt)
          println("Num Total : " + validationStats._2.toInt)
          println("Accuracy : " + validationStats._1 / validationStats._2)
        } else {
          println("MSE for regression:")
          println("Mean Squared Error : " + validationStats._1)
          println("Num Total : " + validationStats._2.toInt)
        }
      }
    } catch {
      case e: Exception => println("Exception:" + e.toString)
    } finally {
      Thread.sleep(config.pauseDuration * 1000)
      sc.stop()
    }
  }
}
