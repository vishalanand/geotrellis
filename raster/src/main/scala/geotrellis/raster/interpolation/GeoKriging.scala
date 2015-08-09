package geotrellis.raster.interpolation

import geotrellis.vector.PointFeature
import geotrellis.vector.Point
import geotrellis.vector.interpolation.{NonLinearSemivariogram, ModelType, Semivariogram}

import org.apache.commons.math3.linear._

import spire.syntax.cfor._

/**
 * @param points          Sample points for Geostatistical Kriging model training
 * @param attrFunc        Attribute matrix transformation for a point (which decides how the point coordinates guide the pointData's value)
 * @param bandwidth       The maximum inter-point pair-distances which influence the prediction
 * @param model           The [[ModelType]] to be used for prediction
 */
class GeoKriging(points: Array[PointFeature[Double]],
                 attrFunc: (Double, Double) => Array[Double],
                 bandwidth: Double,
                 model: ModelType) extends Kriging {
  /**
   * Overloaded constructor, for default attribute matrix generation
   */
  def this(points: Array[PointFeature[Double]], bandwidth: Double, model: ModelType) {
    this(points, (x, y) => Array(x, y, x * x, x * y, y * y), bandwidth, model)
  }

  /**
   * Universal Kriging training with the sample points
   * @param numberOfPoints  Number of points to be Kriged
   */
  protected def createPredictorInit(numberOfPoints: Int): (Double, Double) => (Double, Double) = {
    val n: Int = points.length
    if (n == 0)
      throw new IllegalArgumentException("No points in the training dataset")

    val unitCol: RealMatrix = MatrixUtils.createColumnRealMatrix(Array.fill(n)(1))

    val attrMatrix =
      MatrixUtils.createRealMatrix(Array.tabulate(n)
      { i => Array(1.0) ++ attrFunc(points(i).geom.x, points(i).geom.y) })
    val attrSize: Int = attrMatrix.getColumnDimension - 1

    val scale: RealMatrix =
      new EigenDecomposition(
        MatrixUtils.createRealDiagonalMatrix(
          Array.tabulate(attrSize+1)
          { i => absArray(attrMatrix.getColumn(i)).max }
        )
      ).getSolver.getInverse

    val attrMatrixScaled = attrMatrix.multiply(scale)
    val ptData = MatrixUtils.createColumnRealMatrix(points.map(x => x.data))

    val unscaledBetaOLS =
      new SingularValueDecomposition(attrMatrixScaled)
        .getSolver
        .solve(ptData)

    val betaOLS: RealMatrix = scale.multiply(unscaledBetaOLS)
    val errorOLS: RealMatrix = ptData.subtract(attrMatrix.multiply(betaOLS))

    val pointsFitting: Array[PointFeature[Double]] =
      Array.tabulate(n) { row: Int =>
        PointFeature(points(row).geom, errorOLS.getEntry(row, 0))
      }
    var res: Semivariogram = NonLinearSemivariogram(pointsFitting, 0, 0, model)
    var delta: Double = 1.0
    var counter = 0
    var betaEval = betaOLS

    while (delta > 0.001) {
      counter = counter + 1
      var covariogramMatrixIter: RealMatrix =
        unitCol.multiply(unitCol.transpose())
          .scalarMultiply(res.sill)
          .subtract(varianceMatrixGen(res, points))
          .add(
            MatrixUtils.createRealIdentityMatrix(n)
              .scalarMultiply(res.nugget)
          )
      val rank: Int = new SingularValueDecomposition(covariogramMatrixIter).getRank
      if (rank < covariogramMatrixIter.getRowDimension)
        covariogramMatrixIter = covariogramMatrixIter
          .add(MatrixUtils.createRealIdentityMatrix(n)
          .scalarMultiply(0.0001))
      val covariogramInv =
        new SingularValueDecomposition(new CholeskyDecomposition(covariogramMatrixIter).getL)
          .getSolver.solve(
            MatrixUtils.createRealIdentityMatrix(n)
          )
      val unscaledBeta =
        new SingularValueDecomposition(covariogramInv.multiply(attrMatrixScaled))
          .getSolver.solve(
            covariogramInv.multiply(ptData)
          )
      val beta = scale.multiply(unscaledBeta)
      val errorIter = ptData.subtract(attrMatrix.multiply(beta))
      val pointsFittingIter = Array.tabulate(n) {
        row: Int =>
          PointFeature(points(row).geom, errorIter.getEntry(row, 0))
      }
      val process: Array[Double] = beta.subtract(betaEval).getColumn(0)
      betaEval = beta

      delta = absArray(Array.tabulate(process.length){ i =>
        math.abs(process(i))/betaEval.getEntry(i,0)
      }).max

      if (delta > 0.0001) {
        res = NonLinearSemivariogram(pointsFittingIter, 0, 0, model)
        if(counter > 100)
          delta = 0.00001
      }
    }

    val covariogramMatrix =
      unitCol.multiply(unitCol.transpose())
        .scalarMultiply(res.sill)
        .subtract(varianceMatrixGen(res, points))
        .add(
          MatrixUtils.createRealIdentityMatrix(n)
            .scalarMultiply(res.nugget)
        )
    var i = 0
    val residual: RealMatrix = ptData.subtract(attrMatrix.multiply(betaEval))

    (x: Double, y: Double) =>
      val pointPredict: Point = Point(x, y)
      val distSortedInfo: Array[(Int, Double)] = getPointDistancesSorted(points, attrSize+2, bandwidth, pointPredict)
      val distanceID: Array[Int] = distSortedInfo.map(_._1)
      val localCovarianceInv =
        new SingularValueDecomposition(
          covariogramMatrix.getSubMatrix(distanceID, distanceID)
        ).getSolver.getInverse

      val sortedDist: RealMatrix = MatrixUtils.createColumnRealMatrix(distSortedInfo.map(_._2))
      val localCovVector: RealMatrix =
        unitCol.getSubMatrix(distanceID, Array(0))
          .scalarMultiply(res.sill)
          .subtract(
            MatrixUtils.createRealMatrix(
              Array.tabulate(distanceID.length, 1){ (j, _) =>
                res(sortedDist.getEntry(j,0))
              }
            )
          )

      cfor(0)(_ < distanceID.length, _ + 1) { j: Int =>
        if (sortedDist.getEntry(j, 0) == 0)
          localCovVector.setEntry(j, 0, localCovVector.getEntry(j, 0) + res.nugget)
      }

      val curAttrVal: Array[Double] = Array(1.0) ++ attrFunc(x, y)

      val kPredict: Double = (
        MatrixUtils.createRowRealMatrix(curAttrVal)
          .multiply(betaEval).getEntry(0,0)
          + localCovVector.transpose()
          .multiply(localCovarianceInv)
          .multiply(residual.getSubMatrix(distanceID,Array(0)))
          .getEntry(0,0)
        )

      val colID: Array[Int] = (0 to attrMatrix.getColumnDimension - 1).toArray
      val sampleAttrSelect: RealMatrix = attrMatrix.getSubMatrix(distanceID, colID)
      val attrMatrixScaledSelect: RealMatrix = attrMatrixScaled.getSubMatrix(distanceID, colID)

      val rankTemp: Int =
        new SingularValueDecomposition(
          attrMatrixScaledSelect.transpose()
            .multiply(localCovarianceInv)
            .multiply(attrMatrixScaledSelect)
        ).getRank

      val xtVinvXTemp: RealMatrix = attrMatrixScaledSelect.transpose()
        .multiply(localCovarianceInv)
        .multiply(attrMatrixScaledSelect)
      //X' * VInverse * X
      val xtVinvX: RealMatrix =
        if (rankTemp < attrSize + 1)
          scale.multiply(new SingularValueDecomposition(xtVinvXTemp).getSolver.getInverse)
            .multiply(scale)
        else
          scale.multiply(new EigenDecomposition(xtVinvXTemp).getSolver.getInverse)
            .multiply(scale)

      val kVarTemp =
        MatrixUtils.createColumnRealMatrix(curAttrVal)
          .subtract(
            sampleAttrSelect.transpose()
              .multiply(localCovarianceInv)
              .multiply(localCovVector)
          )

      val kVar: Double =
        math.sqrt(
          res.sill
            - localCovVector.transpose()
            .multiply(localCovarianceInv)
            .multiply(localCovVector).getEntry(0,0)
            + kVarTemp.transpose()
            .multiply(xtVinvX)
            .multiply(kVarTemp).getEntry(0,0)
        )
      i = i + 1

      (kPredict, kVar)
  }
}