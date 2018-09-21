package org.janelia.stitching;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.janelia.dataaccess.DataProvider;
import org.janelia.util.concurrent.SameThreadExecutorService;

import ij.ImagePlus;
import mpicbg.imglib.custom.OffsetValidator;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.exception.ImgLibException;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.IntervalsHelper;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.RandomAccessiblePairNullable;
import net.imglib2.view.Views;

public class StitchSubTilePair< T extends NativeType< T > & RealType< T >, U extends NativeType< U > & RealType< U > >
{
	private final StitchingJob job;
	private final OffsetUncertaintyEstimator offsetUncertaintyEstimator;
	private final List< RandomAccessiblePairNullable< U, U > > flatfieldsForChannels;
	private final List< Map< Integer, TileInfo > > tileMapsForChannels;

	public StitchSubTilePair(
			final StitchingJob job,
			final OffsetUncertaintyEstimator offsetUncertaintyEstimator,
			final List< RandomAccessiblePairNullable< U, U > > flatfieldsForChannels,
			final List< Map< Integer, TileInfo > > tileMapsForChannels
		)
	{
		this.job = job;
		this.offsetUncertaintyEstimator = offsetUncertaintyEstimator;
		this.flatfieldsForChannels = flatfieldsForChannels;
		this.tileMapsForChannels = tileMapsForChannels;
	}

	/**
	 * Estimate pairwise shift vector between a pair of subtiles.
	 * The first subtile of the given pair is considered 'fixed', and the second is 'moving',
	 * that means, the resulting shift vector will effectively be equal to (NewMovingPos - FixedPos).
	 *
	 * @param subTilePair
	 * @throws PipelineExecutionException
	 */
	public SerializablePairWiseStitchingResult stitchSubTilePair( final SubTilePair subTilePair ) throws PipelineExecutionException
	{
		final SubTile[] subTiles = subTilePair.toArray();

		// Get approximate transformations for the tile pair. If it is not the first iteration, they have already been estimated prior to pairwise matching.
		final AffineGet[] estimatedFullTileTransforms = new AffineGet[ subTiles.length ];
		for ( int i = 0; i < subTiles.length; ++i )
			estimatedFullTileTransforms[ i ] = TransformedTileOperations.getTileTransform( subTiles[ i ].getFullTile(), offsetUncertaintyEstimator != null );

		final ErrorEllipse movingSubTileSearchRadius;

		if ( offsetUncertaintyEstimator != null )
		{
			for ( final AffineGet estimatedFullTileTransform : estimatedFullTileTransforms )
				Objects.requireNonNull( estimatedFullTileTransform, "expected non-null affine transform for tile" );

//			try
//			{
//				// get search radius for new moving subtile position in the fixed subtile space
//				final EstimatedRelativeSearchRadius combinedSearchRadiusForMovingSubtile = PairwiseTileOperations.getCombinedSearchRadiusForMovingSubTile( subTiles, offsetUncertaintyEstimator );
//
//				movingSubTileSearchRadius = combinedSearchRadiusForMovingSubtile.combinedErrorEllipse;
//				System.out.println( "Estimated error ellipse of size " + Arrays.toString( Intervals.dimensionsAsLongArray( Intervals.smallestContainingInterval( movingSubTileSearchRadius.estimateBoundingBox() ) ) ) );
//			}
//			catch ( final NotEnoughNeighboringTilesException e )
//			{
//				System.out.println( "Could not estimate error ellipse for pair " + subTilePair );
//				final SerializablePairWiseStitchingResult invalidPairwiseResult = new SerializablePairWiseStitchingResult( subTilePair, null, 0 );
//				return invalidPairwiseResult;
//			}

			// FIXME: use uncorrelated error ellipse for now instead of estimating uncertainty
			final double[] uncorrelatedErrorEllipseRadius = OffsetUncertaintyEstimator.getUncorrelatedErrorEllipseRadius(
					subTilePair.getA().getFullTile().getSize(),
					job.getArgs().errorEllipseRadiusAsTileSizeRatio()
				);
			movingSubTileSearchRadius = OffsetUncertaintyEstimator.getUncorrelatedErrorEllipse( uncorrelatedErrorEllipseRadius );
			System.out.println( "Create uncorrelated error ellipse of size " + Arrays.toString( Intervals.dimensionsAsLongArray( Intervals.smallestContainingInterval( movingSubTileSearchRadius.estimateBoundingBox() ) ) ) + " (instead of estimating uncertainty for now)" );
		}
		else
		{
			if ( job.getArgs().constrainMatchingOnFirstIteration() )
			{
				final double[] uncorrelatedErrorEllipseRadius = OffsetUncertaintyEstimator.getUncorrelatedErrorEllipseRadius(
						subTilePair.getA().getFullTile().getSize(),
						job.getArgs().errorEllipseRadiusAsTileSizeRatio()
					);
				movingSubTileSearchRadius = OffsetUncertaintyEstimator.getUncorrelatedErrorEllipse( uncorrelatedErrorEllipseRadius );
				System.out.println( "Create uncorrelated error ellipse of size " + Arrays.toString( Intervals.dimensionsAsLongArray( Intervals.smallestContainingInterval( movingSubTileSearchRadius.estimateBoundingBox() ) ) ) + " to constrain matching" );
			}
			else
			{
				movingSubTileSearchRadius = null;
			}
		}

		if ( movingSubTileSearchRadius != null )
		{
			movingSubTileSearchRadius.setErrorEllipseTransform(
					PairwiseTileOperations.getErrorEllipseTransform( subTiles, estimatedFullTileTransforms )
				);
		}

		// Render both ROIs in the fixed space
		final InvertibleRealTransform[] affinesToFixedTileSpace = new InvertibleRealTransform[] {
				TransformUtils.createTransform( subTilePair.getA().numDimensions() ), // identity transform for fixed tile
				PairwiseTileOperations.getMovingTileToFixedTileTransform( estimatedFullTileTransforms ) // moving tile to fixed tile
			};

		// convert to fixed subtile space
		final InvertibleRealTransform[] affinesToFixedSubTileSpace = new InvertibleRealTransform[ subTiles.length ];
		for ( int i = 0; i < subTiles.length; ++i )
			affinesToFixedSubTileSpace[ i ] = PairwiseTileOperations.getFixedSubTileTransform( subTiles, affinesToFixedTileSpace[ i ] );

		// TODO: use smaller ROI instead of the whole subtile?
		final ImagePlus[] roiImps = new ImagePlus[ subTiles.length ];
		final Interval[] transformedRoiIntervals = new Interval[ subTiles.length ];
		for ( int i = 0; i < subTiles.length; ++i )
		{
			final Pair< ImagePlus, Interval > roiAndBoundingBox = renderSubTile(
					subTiles[ i ],
					affinesToFixedSubTileSpace[ i ],
					flatfieldsForChannels
				);
			roiImps[ i ] = roiAndBoundingBox.getA();
			transformedRoiIntervals[ i ] = roiAndBoundingBox.getB();
		}
		// ROIs are rendered in the fixed subtile space, validate that the fixed subtile has zero-min
		if ( !Views.isZeroMin( transformedRoiIntervals[ 0 ] ) )
			throw new PipelineExecutionException( "fixed subtile is expected to be zero-min" );

		final SerializablePairWiseStitchingResult pairwiseResult = stitchPairwise( roiImps, movingSubTileSearchRadius );

		if ( pairwiseResult == null )
		{
			System.out.println( "Could not find phase correlation peaks for pair " + subTilePair );
			final SerializablePairWiseStitchingResult invalidPairwiseResult = new SerializablePairWiseStitchingResult( subTilePair, null, 0 );
			return invalidPairwiseResult;
		}

		pairwiseResult.setVariance( computeVariance( roiImps ) );
		pairwiseResult.setSubTilePair( subTilePair );
		pairwiseResult.setEstimatedFullTileTransformPair( new AffineTransformPair( estimatedFullTileTransforms[ 0 ], estimatedFullTileTransforms[ 1 ] ) );

		System.out.println( "Stitched subtile pair " + subTilePair );
		return pairwiseResult;
	}

	/**
	 * Renders the given subtile in the transformed space averaging over all channels and optionally flat-fielding them.
	 * The resulting image is wrapped as {@link ImagePlus}.
	 *
	 * @param subTile
	 * @param fullTileTransform
	 * @param flatfieldsForChannels
	 * @return pair: (rendered image; its world bounding box)
	 * @throws PipelineExecutionException
	 */
	private Pair< ImagePlus, Interval > renderSubTile(
			final SubTile subTile,
			final InvertibleRealTransform fullTileTransform,
			final List< RandomAccessiblePairNullable< U, U > > flatfieldsForChannels ) throws PipelineExecutionException
	{
		final DataProvider dataProvider = job.getDataProvider();
		final double[] normalizedVoxelDimensions = Utils.normalizeVoxelDimensions( subTile.getFullTile().getPixelResolution() );
		System.out.println( "Normalized voxel size = " + Arrays.toString( normalizedVoxelDimensions ) );
		final double[] blurSigmas = new  double[ normalizedVoxelDimensions.length ];
		for ( int d = 0; d < blurSigmas.length; d++ )
			blurSigmas[ d ] = job.getArgs().blurSigma() / normalizedVoxelDimensions[ d ];

		System.out.println( "Averaging corresponding tile images for " + job.getChannels() + " channels" );

		RandomAccessibleInterval< FloatType > avgChannelImg = null;
		Interval roiBoundingBox = null;
		T inputType = null;
		int channelsUsed = 0;

		for ( int channel = 0; channel < job.getChannels(); ++channel )
		{
			final TileInfo tile = tileMapsForChannels.get( channel ).get( subTile.getFullTile().getIndex() );

			// validate that all corresponding tiles have the same grid coordinates
			// (or skip validation if unable to extract grid coordinates from tile filename)
			validateGridCoordinates( subTile, tile );

			// get ROI image
			final RandomAccessibleInterval< T > roiImg;
			try
			{
				roiImg = TransformedTileImageLoader.loadTile(
						tile,
						dataProvider,
						Optional.ofNullable( flatfieldsForChannels.get( channel ) ),
						fullTileTransform,
						IntervalsHelper.roundRealInterval( subTile )
					);
			}
			catch ( final IOException e )
			{
				throw new PipelineExecutionException( e );
			}

			// allocate output image if needed
			if ( avgChannelImg == null )
				avgChannelImg = ArrayImgs.floats( Intervals.dimensionsAsLongArray( roiImg ) );
			else if ( !Intervals.equalDimensions( avgChannelImg, roiImg ) )
				throw new PipelineExecutionException( "different ROI dimensions for the same grid position " + Utils.getTileCoordinatesString( tile ) );

			// set transformed bounding box
			if ( roiBoundingBox == null )
				roiBoundingBox = new FinalInterval( roiImg );
			else if ( !Intervals.equals( roiBoundingBox, roiImg ) )
				throw new PipelineExecutionException( "different ROI coordinates for the same grid position " + Utils.getTileCoordinatesString( tile ) );

			// store input type
			if ( inputType == null )
				inputType = Util.getTypeFromInterval( roiImg ).createVariable();

			// accumulate data in the output image
			final RandomAccessibleInterval< FloatType > srcImg = Converters.convert( roiImg, new RealFloatConverter<>(), new FloatType() );
			final Cursor< FloatType > srcCursor = Views.flatIterable( srcImg ).cursor();
			final Cursor< FloatType > dstCursor = Views.flatIterable( avgChannelImg ).cursor();
			while ( dstCursor.hasNext() || srcCursor.hasNext() )
				dstCursor.next().add( srcCursor.next() );

			++channelsUsed;
		}

		if ( channelsUsed == 0 )
			throw new PipelineExecutionException( subTile.getFullTile().getIndex() + ": images are missing in all channels" );

		// average output image over the number of accumulated channels
		final FloatType denom = new FloatType( channelsUsed );
		final Cursor< FloatType > dstCursor = Views.iterable( avgChannelImg ).cursor();
		while ( dstCursor.hasNext() )
			dstCursor.next().div( denom );

		// blur with requested sigma
		System.out.println( String.format( "Blurring the overlap area of size %s with sigmas=%s", Arrays.toString( Intervals.dimensionsAsLongArray( avgChannelImg ) ), Arrays.toString( blurSigmas ) ) );
		try
		{
			blur( avgChannelImg, blurSigmas );
		}
		catch ( final IncompatibleTypeException e )
		{
			throw new PipelineExecutionException( e );
		}

		// convert back to input data type
		final ImagePlusImg< T, ? > finalImg = new ImagePlusImgFactory< T >().create( avgChannelImg, inputType );
		final Cursor< FloatType > avgChannelImgCursor = Views.flatIterable( avgChannelImg ).cursor();
		final Cursor< T > finalImgCursor = Views.flatIterable( finalImg ).cursor();
		while ( finalImgCursor.hasNext() || avgChannelImgCursor.hasNext() )
			finalImgCursor.next().setReal( avgChannelImgCursor.next().get() );

		final ImagePlus finalImp;
		try
		{
			finalImp = finalImg.getImagePlus();
		}
		catch ( final ImgLibException e )
		{
			throw new PipelineExecutionException( e );
		}

		Utils.workaroundImagePlusNSlices( finalImp );

		return new ValuePair<>( finalImp, roiBoundingBox );
	}

	private < F extends NumericType< F > > void blur( final RandomAccessibleInterval< F > image, final double[] sigmas ) throws IncompatibleTypeException
	{
		final RandomAccessible< F > extendedImage = Views.extendMirrorSingle( image );
		Gauss3.gauss( sigmas, extendedImage, image, new SameThreadExecutorService() );
	}

	// TODO: compute variance only within new overlapping region (after matching)
	static < T extends NativeType< T > & RealType< T > > double computeVariance( final ImagePlus[] roiPartImps )
	{
		double pixelSum = 0, pixelSumSquares = 0;
		long pixelCount = 0;
		for ( int i = 0; i < 2; ++i )
		{
			final RandomAccessibleInterval< T > roiImg = ImagePlusImgs.from( roiPartImps[ i ] );
			final Cursor< T > roiImgCursor = Views.iterable( roiImg ).cursor();
			while ( roiImgCursor.hasNext() )
			{
				final double val = roiImgCursor.next().getRealDouble();
				pixelSum += val;
				pixelSumSquares += Math.pow( val, 2 );
			}
			pixelCount += Intervals.numElements( roiImg );
		}
		final double variance = pixelSumSquares / pixelCount - Math.pow( pixelSum / pixelCount, 2 );
		return variance;
	}

	private SerializablePairWiseStitchingResult stitchPairwise(
			final ImagePlus[] roiImps,
			final OffsetValidator offsetValidator )
	{
		final int timepoint = 1;
		final int numPeaks = 1;
		PairwiseStitchingPerformer.setThreads( 1 );

		final SerializablePairWiseStitchingResult[] results = PairwiseStitchingPerformer.stitchPairwise(
				roiImps[ 0 ], roiImps[ 1 ], timepoint, timepoint,
				job.getParams(), numPeaks,
				offsetValidator
			);

		final SerializablePairWiseStitchingResult result = results[ 0 ];

		if ( result == null )
		{
			// TODO: pass actions to update accumulators
//			noPeaksWithinConfidenceIntervalPairsCount.add( 1 );
			System.out.println( "no peaks found within the confidence interval" );
		}

		return result;
	}

	private void validateGridCoordinates( final SubTile tileBox, final TileInfo tile )
	{
		{
			String error = null;
			try
			{
				if ( !Utils.getTileCoordinatesString( tile ).equals( Utils.getTileCoordinatesString( tileBox.getFullTile() ) ) )
					error = "tile with index " + tile.getIndex() + " has different grid positions: " +
								Utils.getTileCoordinatesString( tile ) + " vs " + Utils.getTileCoordinatesString( tileBox.getFullTile() );
			}
			catch ( final Exception e ) {}

			if ( error != null )
				throw new RuntimeException( error );
		}
	}
}
