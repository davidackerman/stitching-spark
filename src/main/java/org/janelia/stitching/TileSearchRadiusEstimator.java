package org.janelia.stitching;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.IntervalNeighborSearchOnKDTree;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;

public class TileSearchRadiusEstimator implements Serializable
{
	public static class EstimatedWorldSearchRadius
	{
		public final ErrorEllipse errorEllipse;
		public final TileInfo tile;
		public final Set< TileInfo > neighboringTiles;
		public final List< Pair< RealPoint, RealPoint > > stageAndWorldCoordinates;

		public EstimatedWorldSearchRadius(
				final ErrorEllipse errorEllipse,
				final TileInfo tile,
				final Set< TileInfo > neighboringTiles,
				final List< Pair< RealPoint, RealPoint > > stageAndWorldCoordinates )
		{
			this.errorEllipse = errorEllipse;
			this.tile = tile;
			this.neighboringTiles = neighboringTiles;
			this.stageAndWorldCoordinates = stageAndWorldCoordinates;
		}
	}

	public static class EstimatedRelativeSearchRadius
	{
		public final ErrorEllipse combinedErrorEllipse;
		public final EstimatedWorldSearchRadius[] worldSearchRadiusStats;

		public EstimatedRelativeSearchRadius(
				final ErrorEllipse combinedErrorEllipse,
				final EstimatedWorldSearchRadius[] worldSearchRadiusStats )
		{
			this.combinedErrorEllipse = combinedErrorEllipse;
			this.worldSearchRadiusStats = worldSearchRadiusStats;

			for ( int d = 0; d < combinedErrorEllipse.numDimensions(); ++d )
				if ( !Util.isApproxEqual( combinedErrorEllipse.getEllipseCenter()[ d ], 0, 1e-10 ) )
					throw new IllegalArgumentException( "A combined error ellipse is expected to be defined in relative coordinate space and be zero-centered" );
		}
	}

	public static class NotEnoughNeighboringTilesException extends Exception
	{
		private static final long serialVersionUID = -6612788597298487220L;

		public final Set< TileInfo > neighboringTiles;
		public final int numNeighboringTilesRequested;

		public NotEnoughNeighboringTilesException( final Set< TileInfo > neighboringTiles, final int numNeighboringTilesRequested )
		{
			super( "Requested " + numNeighboringTilesRequested + " neighboring tiles, found only " + neighboringTiles.size() );
			this.neighboringTiles = neighboringTiles;
			this.numNeighboringTilesRequested = numNeighboringTilesRequested;
		}
	}

	private static final long serialVersionUID = 3966655006478467424L;

	private final double[] estimationWindowSize;
	private final double searchRadiusMultiplier;
	private final int minNumNeighboringTiles;
	private final int subdivisionGridSize;
	private final boolean weighted;
	private final KDTree< TileInfo > kdTree;

	public TileSearchRadiusEstimator(
			final TileInfo[] tiles,
			final double[] estimationWindowSize,
			final double searchRadiusMultiplier,
			final int minNumNeighboringTiles,
			final int subdivisionGridSize,
			final boolean weighted )
	{
		this.estimationWindowSize = estimationWindowSize;
		this.searchRadiusMultiplier = searchRadiusMultiplier;
		this.minNumNeighboringTiles = minNumNeighboringTiles;
		this.subdivisionGridSize = subdivisionGridSize;
		this.weighted = weighted;

		final List< TileInfo > tilesWithStitchedTransform = new ArrayList<>();
		for ( final TileInfo tile : tiles )
			if ( tile.getTransform() != null )
				tilesWithStitchedTransform.add( tile );

		final List< RealLocalizable > stageSubsetPositions = new ArrayList<>();
		for ( final TileInfo tileWithStitchedTransform : tilesWithStitchedTransform )
			stageSubsetPositions.add( new RealPoint( tileWithStitchedTransform.getStagePosition() ) );

		// build a search tree to be able to look up stage positions and corresponding stitched positions
		// in the neighborhood for any given stage position
		kdTree = new KDTree<>( tilesWithStitchedTransform, stageSubsetPositions );
	}

	public double[] getEstimationWindowSize() { return estimationWindowSize; }
	public double getSearchRadiusMultiplier() { return searchRadiusMultiplier; }
	public int getMinNumNeighboringTiles() { return minNumNeighboringTiles; }
	public int getSubdivisionGridSize() { return subdivisionGridSize; }
	public boolean isWeighted() { return weighted; }

	public EstimatedWorldSearchRadius estimateSearchRadiusWithinWindow( final TileInfo tile ) throws PipelineExecutionException, NotEnoughNeighboringTilesException
	{
		return estimateSearchRadiusWithinWindow( tile, getEstimationWindow( tile ) );
	}

	public EstimatedWorldSearchRadius estimateSearchRadiusWithinWindow( final TileInfo tile, final Interval estimationWindow ) throws PipelineExecutionException, NotEnoughNeighboringTilesException
	{
		return estimateSearchRadius( tile, findTilesWithinWindow( estimationWindow ) );
	}

	public EstimatedWorldSearchRadius estimateSearchRadiusKNearestNeighbors( final TileInfo tile, final int numNearestNeighbors ) throws PipelineExecutionException, NotEnoughNeighboringTilesException
	{
		return estimateSearchRadius(
				tile,
				findNearestTiles(
						new RealPoint( tile.getStagePosition() ),
						numNearestNeighbors
					)
			);
	}

	public Collection< SubTile > findSubTilesWithinWindow( final SubTile subTile )
	{
		return findSubTilesWithinWindow( subTile, getEstimationWindow( subTile.getFullTile() ) );
	}

	public Collection< SubTile > findSubTilesWithinWindow( final SubTile subTile, final Interval window )
	{
		return getNeighboringSubTiles( subTile, findTilesWithinWindow( window ) );
	}

	private Collection< SubTile > getNeighboringSubTiles( final SubTile subTile, final Set< TileInfo > neighboringTiles )
	{
		final Collection< SubTile > neighboringSubTiles = new ArrayList<>();
		final int[] subTilesGridSize = new int[ subTile.numDimensions() ];
		Arrays.fill( subTilesGridSize, subdivisionGridSize );
		for ( final TileInfo neighboringTile : neighboringTiles )
		{
			for ( final SubTile neighboringSubTile : SubTileOperations.subdivideTiles( new TileInfo[] { neighboringTile }, subTilesGridSize ) )
				if ( Intervals.equals( neighboringSubTile, subTile ) )
					neighboringSubTiles.add( neighboringSubTile );
		}
		if ( neighboringSubTiles.size() != neighboringTiles.size() )
			throw new RuntimeException( "neighboringTiles size: " + neighboringTiles.size() + ", neighboringSubTiles size: " + neighboringSubTiles.size() );
		return neighboringSubTiles;
	}

	public Set< TileInfo > findTilesWithinWindow( final Interval estimationWindow )
	{
		final IntervalNeighborSearchOnKDTree< TileInfo > intervalSearch = new IntervalNeighborSearchOnKDTree<>( kdTree );
		final Set< TileInfo > neighboringTiles = new HashSet<>( intervalSearch.search( estimationWindow ) );
		return neighboringTiles;
	}

	public Set< TileInfo > findNearestTiles( final RealPoint point, final int numNearestNeighbors )
	{
		final KNearestNeighborSearchOnKDTree< TileInfo > neighborsSearch = new KNearestNeighborSearchOnKDTree<>( kdTree, numNearestNeighbors );
		neighborsSearch.search( point );
		final Set< TileInfo > neighboringTiles = new HashSet<>();
		for ( int i = 0; i < neighborsSearch.getK(); ++i )
			neighboringTiles.add( neighborsSearch.getSampler( i ).get() );
		return neighboringTiles;
	}

	private EstimatedWorldSearchRadius estimateSearchRadius( final TileInfo tile, final Set< TileInfo > neighboringTiles ) throws PipelineExecutionException, NotEnoughNeighboringTilesException
	{
		// do not use the tile in its offset statistics for prediction
		neighboringTiles.removeIf( t -> t.getIndex().equals( tile.getIndex() ) );

		if ( neighboringTiles.size() < minNumNeighboringTiles )
			throw new NotEnoughNeighboringTilesException( neighboringTiles, minNumNeighboringTiles );

		final List< Pair< RealPoint, RealPoint > > stageAndWorldCoordinates = getStageAndWorldCoordinates( neighboringTiles );

		final List< double[] > offsetSamples = new ArrayList<>();
		for ( final Pair< RealPoint, RealPoint > stageAndWorld : stageAndWorldCoordinates )
		{
			final double[] offsetSample = new double[ tile.numDimensions() ];
			for ( int d = 0; d < offsetSample.length; ++d )
				offsetSample[ d ] = stageAndWorld.getB().getDoublePosition( d ) - stageAndWorld.getA().getDoublePosition( d );
			offsetSamples.add( offsetSample );
		}

		final List< Double > offsetSamplesWeights = new ArrayList<>();
		for ( final Pair< RealPoint, RealPoint > stageAndWorld : stageAndWorldCoordinates )
			offsetSamplesWeights.add( weighted ? getSampleWeight( tile, stageAndWorld.getA() ) : 1 );
		normalizeWeights( offsetSamplesWeights );

		final double[] meanOffset = new double[ tile.numDimensions() ];
		for ( int i = 0; i < offsetSamples.size(); ++i )
		{
			final double[] offsetSample = offsetSamples.get( i );
			final double offsetSampleWeight = offsetSamplesWeights.get( i );
			for ( int d = 0; d < meanOffset.length; ++d )
				meanOffset[ d ] += offsetSample[ d ] * offsetSampleWeight;
		}

		final double[][] covarianceMatrix = new double[ meanOffset.length ][ meanOffset.length ];
		double covarianceDenomCoeff = 0;
		for ( final double offsetSampleWeight : offsetSamplesWeights )
			covarianceDenomCoeff += Math.pow( offsetSampleWeight, 2 );
		covarianceDenomCoeff = 1 - covarianceDenomCoeff;
		for ( int dRow = 0; dRow < covarianceMatrix.length; ++dRow )
		{
			for ( int dCol = dRow; dCol < covarianceMatrix[ dRow ].length; ++dCol )
			{
				double dRowColOffsetSumProduct = 0;
				for ( int i = 0; i < offsetSamples.size(); ++i )
				{
					final double[] offsetSample = offsetSamples.get( i );
					final double offsetSampleWeight = offsetSamplesWeights.get( i );
					dRowColOffsetSumProduct += ( offsetSample[ dRow ] - meanOffset[ dRow ] ) * ( offsetSample[ dCol ] - meanOffset[ dCol ] ) * offsetSampleWeight;
				}
				final double covariance = dRowColOffsetSumProduct / covarianceDenomCoeff;
				covarianceMatrix[ dRow ][ dCol ] = covarianceMatrix[ dCol ][ dRow ] = covariance;
			}
		}

		final ErrorEllipse searchRadius = new ErrorEllipse( searchRadiusMultiplier, meanOffset, covarianceMatrix );
		return new EstimatedWorldSearchRadius( searchRadius, tile, neighboringTiles, stageAndWorldCoordinates );
	}

	private static double getSampleWeight( final TileInfo tile, final RealPoint point )
	{
		final RealPoint tileStagePoint = new RealPoint( tile.getStagePosition() );
		double distanceSqr = 0;
		for ( int d = 0; d < Math.max( tileStagePoint.numDimensions(), point.numDimensions() ); ++d )
			distanceSqr += Math.pow( point.getDoublePosition( d ) - tileStagePoint.getDoublePosition( d ), 2 );

		if ( distanceSqr < 1 )
			throw new IllegalArgumentException( "The sample point is too close to the tile" );

		return 1. / distanceSqr;
	}

	private static void normalizeWeights( final List< Double > weights )
	{
		double sumWeights = 0;
		for ( final double weight : weights )
			sumWeights += weight;

		for ( int i = 0; i < weights.size(); ++i )
			weights.set( i, weights.get( i ) / sumWeights );
	}

	public static double[] getEstimationWindowSize( final long[] tileSize, final int[] statsWindowTileSize )
	{
		final double[] estimationWindowSize = new double[ tileSize.length ];
		for ( int d = 0; d < tileSize.length; ++d )
			estimationWindowSize[ d ] = tileSize[ d ] * statsWindowTileSize[ d ];
		return estimationWindowSize;
	}

	public static double[] getUncorrelatedErrorEllipseRadius( final long[] tileSize, final double errorEllipseRadiusAsTileSizeRatio )
	{
		final double[] radius = new double[ tileSize.length ];
		for ( int d = 0; d < radius.length; ++d )
			radius[ d ] = tileSize[ d ] * errorEllipseRadiusAsTileSizeRatio;
		return radius;
	}

	public static ErrorEllipse getUncorrelatedErrorEllipse( final double[] radius ) throws PipelineExecutionException
	{
		final double[] zeroMeanValues = new double[ radius.length ];
		final double[][] uncorrelatedCovarianceMatrix = new double[ radius.length ][ radius.length ];
		for ( int d = 0; d < radius.length; ++d )
			uncorrelatedCovarianceMatrix[ d ][ d ] = radius[ d ] * radius[ d ];
		return new ErrorEllipse( 1.0, zeroMeanValues, uncorrelatedCovarianceMatrix );
	}

	static List< Pair< RealPoint, RealPoint > > getStageAndWorldCoordinates( final Set< TileInfo > neighboringTiles )
	{
		final List< Pair< RealPoint, RealPoint > > stageAndWorldCoordinates = new ArrayList<>();
		for ( final TileInfo neighboringTile : neighboringTiles )
		{
			// invert the linear part of the affine transformation
			final AffineGet neighboringTileTransform = TransformedTileOperations.getTileTransform( neighboringTile, true );
			final RealTransform neighboringTileLocalToOffsetTransform = TransformUtils.undoLinearComponent( neighboringTileTransform );

			final double[] stagePosition = neighboringTile.getStagePosition();
			final double[] transformedPosition = new double[ neighboringTile.numDimensions() ];
			neighboringTileLocalToOffsetTransform.apply( new double[ neighboringTile.numDimensions() ], transformedPosition );

			stageAndWorldCoordinates.add( new ValuePair<>( new RealPoint( stagePosition ), new RealPoint( transformedPosition ) ) );
		}
		return stageAndWorldCoordinates;
	}

	/**
	 * When estimating a pairwise shift vector between a pair of tiles, both of them have
	 * an expected offset and a confidence interval where they can be possibly shifted.
	 *
	 * For pairwise matching, one of the tiles is 'fixed' and the other one is 'moving'.
	 * This function combines these confidence intervals of the two tiles to just one interval that represents
	 * variability of the new offset between them vs. stage offset between them.
	 *
	 * @param worldSearchRadiusStats
	 * @return
	 * @throws PipelineExecutionException
	 */
	public EstimatedRelativeSearchRadius getCombinedCovariancesSearchRadius( final EstimatedWorldSearchRadius[] worldSearchRadiusStats ) throws PipelineExecutionException
	{
		final int dim = worldSearchRadiusStats[ 0 ].errorEllipse.numDimensions();
		final double[][] combinedOffsetsCovarianceMatrix = new double[ dim ][ dim ];
		for ( final EstimatedWorldSearchRadius worldSearchRadius : worldSearchRadiusStats )
			for ( int dRow = 0; dRow < dim; ++dRow )
				for ( int dCol = 0; dCol < dim; ++dCol )
					combinedOffsetsCovarianceMatrix[ dRow ][ dCol ] += worldSearchRadius.errorEllipse.getOffsetsCovarianceMatrix()[ dRow ][ dCol ];

		return new EstimatedRelativeSearchRadius(
				new ErrorEllipse(
						searchRadiusMultiplier,
						new double[ dim ], // zero-centered
						combinedOffsetsCovarianceMatrix
					),
				worldSearchRadiusStats
			);
	}

	Interval getEstimationWindow( final TileInfo tile )
	{
		return getEstimationWindow( new RealPoint( tile.getStagePosition() ) );
	}

	Interval getEstimationWindow( final RealPoint point )
	{
		final long[] estimationWindowMin = new long[ estimationWindowSize.length ], estimationWindowMax = new long[ estimationWindowSize.length ];
		for ( int d = 0; d < estimationWindowSize.length; ++d )
		{
			estimationWindowMin[ d ] = ( long ) Math.floor( point.getDoublePosition( d ) - estimationWindowSize[ d ] / 2 );
			estimationWindowMax[ d ] = ( long ) Math.ceil ( point.getDoublePosition( d ) + estimationWindowSize[ d ] / 2 );
		}
		return new FinalInterval( estimationWindowMin, estimationWindowMax );
	}
}
