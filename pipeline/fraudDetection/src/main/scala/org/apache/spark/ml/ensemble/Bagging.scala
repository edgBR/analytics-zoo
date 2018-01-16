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

package org.apache.spark.ml.ensemble

import org.apache.spark.annotation.Experimental
import org.apache.spark.ml._
import org.apache.spark.ml.feature.StratifiedSampler
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared.HasSeed
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DoubleType, LongType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, Row}

import scala.util.Random

/**
 * Params for [[Bagging]] and [[BaggingModel]].
 */
private[ml] trait BaggingParams[M <: Model[M]]
  extends PredictorParams with HasSeed {
  /**
   * Param for indicating whether bagged model is a classifier (true) or regressor (false).
   * This parameter affects how models are aggregated: voting is used for classification (with ties
   * broken arbitrarily) and averaging is used for regression.
   * Default: true (classification)
   * @group param
   */
  val isClassifier: BooleanParam = new BooleanParam(this, "isClassification",
    "indicates if bagged model is a classifier or regressor")

  /** @group getParam */
  def getIsClassifier: Boolean = $(isClassifier)

  /**
   * Param for number of bootstraped models.
   * Default: 3
   * @group param
   */
  val numModels: IntParam = new IntParam(this, "numModels",
    "number of models to train on bootstrapped samples (>=1)", ParamValidators.gtEq(1))

  /** @group getParam */
  def getNumModels: Int = $(numModels)

  val threshold: IntParam = new IntParam(this, "threshold",
    "threshold", ParamValidators.gtEq(1))

  /** @group getParam */
  def getThreshold: Int = $(threshold)

  setDefault(numModels-> 3, isClassifier->true, threshold -> 2)
}

/**
 * :: Experimental ::
 * Trains an ensemble of models using bootstrap aggregation. Given a dataset with N points,
 * the traditional bootstrap sample consists of N points sampled with replacement from the original
 * dataset. This class generates `numModels` bootstrap samples and uses `estimator` to train a model
 * on each sample. The predictions generated by the trained models are then aggregated to generate
 * the ensemble prediction.
 */
@Experimental
class Bagging[
    M <: Model[M]](override val uid: String)
  extends Estimator[BaggingModel[M]]
  with BaggingParams[M] {

  def this() = this(Identifiable.randomUID("bagging"))

  /** @group setParam */
  def setSeed(value: Long): this.type = set(seed, value)

  /** @group setParam */
  def setNumModels(value: Int): this.type = set(numModels, value)

  /** @group setParam */
  def setIsClassifier(value: Boolean): this.type = set(isClassifier, value)

  /** @group setParam */
  def setFeaturesCol(value: String): this.type = set(featuresCol, value)

  /** @group setParam */
  def setLabelCol(value: String): this.type = set(labelCol, value)

  /** @group setParam */
  def setPredictionCol(value: String): this.type = set(predictionCol, value)

  /** @group setParam */
  def setThreshold(value: Int): this.type = set(threshold, value)

  /**
   * Param for the [[predictor]] to be validated.
   * @group param
   */
  val predictor: Param[Estimator[M]] =
    new Param(this, "estimator", "estimator for bagging")

  /** @group getParam */
  def getPredictor: Estimator[M] = $(predictor)

  /** @group setParam */
  def setPredictor(value: Estimator[M]): this.type = set(predictor, value)

  override def fit(dataset: Dataset[_]): BaggingModel[M] = {
    Random.setSeed($(seed))
    val models = (0 until $(numModels)).map { _ =>
      val sampler = new StratifiedSampler(Map(2 -> 0.05, 1-> 10, 0 -> 1)).setLabel("Class")
      val bootstrapSample = sampler.transform(dataset).cache()
      val dlClassifier = $(predictor).copy(ParamMap.empty)
      val subModel = dlClassifier.fit(bootstrapSample).asInstanceOf[M]
      subModel
    }
    copyValues(new BaggingModel[M](uid, models).setParent(this))
  }

  override def transformSchema(schema: StructType): StructType = {
    $(predictor).transformSchema(schema)
  }

  override def copy(extra: ParamMap): Bagging[M] = {
    val copied = defaultCopy(extra).asInstanceOf[Bagging[M]]
    if (copied.isDefined(predictor)) {
      copied.setPredictor(copied.getPredictor.copy(extra))
    }
    copied
  }
}

/**
 * :: Experimental ::
 * Model from bootstrap aggregating (bagging).
 *
 * TODO: type-safe way to ensure models has at least one
 */
@Experimental
class BaggingModel[M <: Model[M]] private[ml] (
    override val uid: String,
    val models: Seq[M])
  extends Model[BaggingModel[M]]
  with BaggingParams[M] {

  assert(models.size > 0,
    s"BaggingModel requires > 0 models to aggregate over, got ${models.size}")

  /** @group setParam */
  def setFeaturesCol(value: String): this.type = set(featuresCol, value)

  /** @group setParam */
  def setPredictionCol(value: String): this.type = set(predictionCol, value)

  /** @group setParam */
  def setThreshold(value: Int): this.type = set(threshold, value)

  override def transform(dataset: Dataset[_]): DataFrame = {
    transformSchema(dataset.schema, logging = true)

    import dataset.sparkSession.implicits._
    val predicts = models.map { m =>
      m.asInstanceOf[M]
        .transform(dataset)
        .select($(predictionCol)).as[Double].rdd
    }
    val aggPredict: RDD[Double] = predicts.reduce { (rdd1: RDD[Double], rdd2: RDD[Double]) =>
      val result = rdd1.zip(rdd2).map { case (p1, p2) => p1 + p2 }
      result
    }

    val rows = dataset.toDF().rdd.zip(aggPredict).map { case (row, p) =>
      val q = if(p >= $(threshold)) 0.0 else 1.0
      Row.fromSeq(row.toSeq ++ Seq(q))
    }

    val predictDF = dataset.sparkSession.createDataFrame(rows, dataset.schema.add(StructField($(predictionCol), DoubleType)))

    predictDF
  }

  override def transformSchema(schema: StructType): StructType = {
    models.head.transformSchema(schema)
  }

  override def copy(extra: ParamMap): BaggingModel[M] = {
    val copied = new BaggingModel[M](
      uid,
      models.map(_.copy(extra)))
    copyValues(copied, extra).setParent(parent)
  }
}

private object Bagging {
  def dfZipWithIndex(
      df: DataFrame,
      offset: Int = 1,
      colName: String = "id",
      inFront: Boolean = true) : DataFrame = {
    df.sqlContext.createDataFrame(
      df.rdd.zipWithIndex.map(ln =>
        Row.fromSeq(
          (if (inFront) Seq(ln._2 + offset) else Seq())
            ++ ln._1.toSeq ++
            (if (inFront) Seq() else Seq(ln._2 + offset))
        )
      ),
      StructType(
        (if (inFront) Array(StructField(colName, LongType, false)) else Array[StructField]()) ++
          df.schema.fields ++
          (if (inFront) Array[StructField]() else Array(StructField(colName, LongType, false)))
      )
    )
  }
}