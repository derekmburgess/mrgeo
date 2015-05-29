/*
 * Copyright 2009-2015 DigitalGlobe, Inc.
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
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mrgeo.utils

import java.net.URL
import java.util.{Enumeration, Properties}

import org.apache.hadoop.mapreduce.{InputFormat, Job}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.mrgeo.data.DataProviderFactory
import org.mrgeo.data.image.MrsImageDataProvider
import org.mrgeo.data.raster.RasterWritable
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.image.{ImageStats, MrsImagePyramidMetadata}

import scala.collection.mutable

object SparkUtils {

  def loadMrsPyramid(imageName: String, context: SparkContext):
  (RDD[(TileIdWritable, RasterWritable)], MrsImagePyramidMetadata) = {
    val keyclass = classOf[TileIdWritable]
    val valueclass = classOf[RasterWritable]

    // build a phony job...
    val job = new Job()

    val providerProps: Properties = null
    val dp: MrsImageDataProvider = DataProviderFactory.getMrsImageDataProvider(imageName,
      DataProviderFactory.AccessMode.READ, providerProps)
    val metadata: MrsImagePyramidMetadata = dp.getMetadataReader.read()

    MrsImageDataProvider.setupMrsPyramidSingleSimpleInputFormat(job, imageName, providerProps)

    val inputFormatClass: Class[InputFormat[TileIdWritable, RasterWritable]] = job.getInputFormatClass
        .asInstanceOf[Class[InputFormat[TileIdWritable, RasterWritable]]]
    val image = context.newAPIHadoopRDD(job.getConfiguration,
      inputFormatClass,
      keyclass,
      valueclass).persist(StorageLevel.MEMORY_AND_DISK_SER)

    (image, metadata)
  }

  def calculateStats(rdd: RDD[(TileIdWritable, RasterWritable)], bands: Int,
      nodata: Array[Double]): Array[ImageStats] = {

    val zero = Array.ofDim[ImageStats](bands)

    for (i <- 0 until zero.length) {
      zero(i) = new ImageStats(Double.MaxValue, Double.MinValue, 0, 0)
    }

    val stats = rdd.aggregate(zero)((stats, t) => {
      val tile = RasterWritable.toRaster(t._2)

      val tilestat = stats.clone()

      for (y <- 0 until tile.getHeight) {
        for (x <- 0 until tile.getWidth) {
          for (b <- 0 until tile.getNumBands) {
            val p = tile.getSampleDouble(x, y, b)
            if (nodata(b).isNaN && !p.isNaN || nodata(b) != p) {
              tilestat(b).count += 1
              tilestat(b).sum += p
              tilestat(b).max = Math.max(tilestat(b).max, p)
              tilestat(b).min = Math.min(tilestat(b).min, p)
            }
          }
        }
      }

      tilestat
    },
      (stat1, stat2) => {
        val aggstat = stat1.clone()

        for (b <- 0 until aggstat.length) {
          aggstat(b).count += stat2(b).count
          aggstat(b).sum += stat2(b).sum
          aggstat(b).max = Math.max(aggstat(b).max, stat2(b).max)
          aggstat(b).min = Math.min(aggstat(b).min, stat2(b).min)
        }

        aggstat
      })

    for (i <- 0 until stats.length) {
      if (stats(i).count > 0) {
        stats(i).mean = stats(i).sum / stats(i).count
      }
    }

    stats
  }

  def calculateBounds(rdd: RDD[(TileIdWritable, RasterWritable)], zoom:Int, tilesize:Int): Bounds = {

    val bounds = rdd.aggregate(new Bounds())((bounds, t) => {
      val tile = TMSUtils.tileid(t._1.get, zoom)

      val tb = TMSUtils.tileBounds(tile.tx, tile.ty, zoom, tilesize).asBounds()
      tb.expand(bounds)

      tb
    },
      (tb1, tb2) => {
        tb1.expand(tb2)

        tb1
      })

    bounds
  }


  def humantokb(human:String):Int = {
    //val pre: Char = new String ("KMGTPE").charAt (exp - 1)
    val trimmed = human.trim.toLowerCase
    val units = trimmed.charAt(trimmed.length - 1)
    val exp = units match {
    case 'k' => 0
    case 'm' => 1
    case 'g' => 2
    case 'p' => 3
    case 'e' => 4
    case _ => return trimmed.substring(0, trimmed.length - 2).toInt
    }

    val mult = Math.pow(1024, exp).toInt

    val v:Int = trimmed.substring(0, trimmed.length - 1).toInt
    v * mult
  }

  def kbtohuman(kb:Long, maxUnit:String = null):String = {
    if (kb == 0) {
      "0"
    }
    else {
      val suffix = new String("kmgtpe")
      val unit = 1024
      var exp: Int = (Math.log(kb) / Math.log(unit)).toInt

      if (maxUnit != null) {
        val maxexp = suffix.indexOf(maxUnit.trim.toLowerCase)
        if (maxexp > 0 && exp > maxexp)
        {
          exp = maxexp
        }
      }

      val pre: Char = suffix.charAt(exp)

      "%d%s".format((kb / Math.pow(unit, exp)).toInt, pre)
    }
  }

  def jarForClass(clazz:String, cl:ClassLoader = null): String = {
    // now the hard part, need to look in the dependencies...
    val classFile: String = clazz.replaceAll("\\.", "/") + ".class"

    var iter: Enumeration[URL] = null

    if (cl != null) {
      iter = cl.getResources(classFile)
    }
    else
    {
      val cll = getClass.getClassLoader
      iter = cll.getResources(classFile)
    }

    while (iter.hasMoreElements) {
      val url: URL = iter.nextElement
      if (url.getProtocol == "jar") {
        val path: String = url.getPath
        if (path.startsWith("file:")) {
          // strip off the "file:" and "!<classname>"
          return path.substring("file:".length).replaceAll("!.*$", "")
        }
      }
    }

    null
  }

  def jarsForClass(clazz:String, cl:ClassLoader = null): Array[String] = {
    // now the hard part, need to look in the dependencies...
    val classFile: String = clazz.replaceAll("\\.", "/") + ".class"

    jarsForPackage(classFile, cl)
  }

  def jarsForPackage(pkg:String, cl:ClassLoader = null): Array[String] = {
    // now the hard part, need to look in the dependencies...
    var iter: Enumeration[URL] = null

    val pkgFile: String = pkg.replaceAll("\\.", "/")

    if (cl != null) {
      iter = cl.getResources(pkgFile)
    }
    else
    {
      val cll = getClass.getClassLoader
      iter = cll.getResources(pkgFile)
    }

    val ab:mutable.ArrayBuilder[String] = mutable.ArrayBuilder.make()
    while (iter.hasMoreElements) {
      val url: URL = iter.nextElement
      if (url.getProtocol == "jar") {
        val path: String = url.getPath
        if (path.startsWith("file:")) {
          // strip off the "file:" and "!<classname>"
          ab += path.substring("file:".length).replaceAll("!.*$", "")
        }
      }
    }

    ab.result()
  }

}
