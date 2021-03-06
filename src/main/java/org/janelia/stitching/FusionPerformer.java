package org.janelia.stitching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.dataaccess.DataProvider;
import org.janelia.flatfield.FlatfieldCorrectedRandomAccessible;

import bdv.export.Downsample;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.img.list.ListImg;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AbstractTranslation;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.IntervalsNullable;
import net.imglib2.view.RandomAccessiblePair;
import net.imglib2.view.RandomAccessiblePairNullable;
import net.imglib2.view.Views;

public class FusionPerformer
{
	public static enum FusionMode
	{
		MAX_MIN_DISTANCE,
		BLENDING
	}

	private final static double FRACTION_BLENDED = 0.2;

	public static < T extends RealType< T > & NativeType< T > > ImagePlusImg< T, ? > fuseTilesWithinCell(
			final DataProvider dataProvider,
			final FusionMode mode,
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval,
			final T dataType,
			final Number backgroundValue ) throws Exception
	{
		return fuseTilesWithinCell( dataProvider, mode, tilesWithinCell, targetInterval, dataType, backgroundValue, null );
	}

	public static <
		T extends RealType< T > & NativeType< T >,
		U extends RealType< U > & NativeType< U > >
	ImagePlusImg< T, ? > fuseTilesWithinCell(
			final DataProvider dataProvider,
			final FusionMode mode,
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval,
			final T dataType,
			final Number backgroundValue,
			final RandomAccessiblePairNullable< U, U > flatfield ) throws Exception
	{
		return fuseTilesWithinCell( dataProvider, mode, tilesWithinCell, targetInterval, dataType, backgroundValue, flatfield, null );
	}

	public static <
		T extends RealType< T > & NativeType< T >,
		U extends RealType< U > & NativeType< U > >
	ImagePlusImg< T, ? > fuseTilesWithinCell(
			final DataProvider dataProvider,
			final FusionMode mode,
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval,
			final T dataType,
			final Number backgroundValue,
			final RandomAccessiblePairNullable< U, U > flatfield,
			final Map< Integer, Set< Integer > > pairwiseConnectionsMap ) throws Exception
	{
		switch ( mode )
		{
		case MAX_MIN_DISTANCE:
			return fuseTilesWithinCellUsingMaxMinDistance( dataProvider, tilesWithinCell, targetInterval, dataType, backgroundValue, flatfield, pairwiseConnectionsMap );
		case BLENDING:
			return fuseTilesWithinCellUsingBlending( dataProvider, tilesWithinCell, targetInterval, dataType, backgroundValue, flatfield, pairwiseConnectionsMap );
		default:
			throw new RuntimeException( "Unknown fusion mode" );
		}
	}


	public static <
		T extends RealType< T > & NativeType< T >,
		U extends RealType< U > & NativeType< U >,
		R extends RealType< R > & NativeType< R > >
	ImagePlusImg< T, ? > fuseTilesWithinCellUsingBlending(
			final DataProvider dataProvider,
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval,
			final T dataType,
			final Number backgroundValue,
			final RandomAccessiblePairNullable< U, U > flatfield,
			final Map< Integer, Set< Integer > > pairwiseConnectionsMap ) throws Exception
	{
		// initialize helper images for blending fusion strategy
		final RandomAccessibleInterval< FloatType > weights = ArrayImgs.floats( Intervals.dimensionsAsLongArray( targetInterval ) );
		final RandomAccessibleInterval< FloatType > values = ArrayImgs.floats( Intervals.dimensionsAsLongArray( targetInterval ) );

		// initialize helper image for tile connections when exporting only overlaps
		final RandomAccessibleInterval< Set< Integer > > tileIndexes;
		if ( pairwiseConnectionsMap != null )
		{
			final int numElements = ( int ) Intervals.numElements( targetInterval );
			final List< Set< Integer > > tileIndexesList = new ArrayList<>( numElements );
			for ( int i = 0; i < numElements; ++i )
				tileIndexesList.add( new HashSet<>() );
			tileIndexes = new ListImg<>( tileIndexesList, Intervals.dimensionsAsLongArray( targetInterval ) );
		}
		else
		{
			tileIndexes = null;
		}

		for ( final TileInfo tile : tilesWithinCell )
		{
			System.out.println( "Loading tile image " + tile.getFilePath() );
			final Dimensions tileDimensions = tile.getBoundaries();

			final FinalRealInterval intersection = IntervalsNullable.intersectReal(
					new FinalRealInterval( tile.getPosition(), tile.getMax() ),
					targetInterval );

			if ( intersection == null )
				throw new IllegalArgumentException( "tilesWithinCell contains a tile that doesn't intersect with the target interval:\n" + "Tile " + tile.getIndex() + " at " + Arrays.toString( tile.getPosition() ) + " of size " + Arrays.toString( tile.getSize() ) + "\n" + "Output cell " + " at " + Arrays.toString( Intervals.minAsIntArray( targetInterval ) ) + " of size " + Arrays.toString( Intervals.dimensionsAsIntArray( targetInterval ) ) );

			final double[] offset = new double[ targetInterval.numDimensions() ];
			final long[] minIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			final long[] maxIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			for ( int d = 0; d < minIntersectionInTargetInterval.length; ++d )
			{
				offset[ d ] = tile.getPosition( d ) - targetInterval.min( d );
				minIntersectionInTargetInterval[ d ] = ( long ) Math.floor( intersection.realMin( d ) ) - targetInterval.min( d );
				maxIntersectionInTargetInterval[ d ] = ( long ) Math.ceil ( intersection.realMax( d ) ) - targetInterval.min( d );
			}
			final Interval intersectionIntervalInTargetInterval = new FinalInterval( minIntersectionInTargetInterval, maxIntersectionInTargetInterval );
			final Translation translation = new Translation( offset );

			final RandomAccessibleInterval< T > rawTile = TileLoader.loadTile( tile, dataProvider );
			final RandomAccessibleInterval< R > convertedTile = ( RandomAccessibleInterval ) Converters.convert( rawTile, new RealFloatConverter<>(), new FloatType() );
			final RandomAccessible< R > extendedTile = Views.extendBorder( convertedTile );
			final RealRandomAccessible< R > interpolatedTile = Views.interpolate( extendedTile, new NLinearInterpolatorFactory<>() );
			final RandomAccessible< R > rasteredInterpolatedTile = Views.raster( RealViews.affine( interpolatedTile, translation ) );
			final RandomAccessibleInterval< R > interpolatedTileInterval = Views.interval( rasteredInterpolatedTile, intersectionIntervalInTargetInterval );

			final RandomAccessibleInterval< R > sourceInterval;
			if ( flatfield != null )
			{
				final RandomAccessible< U >[] flatfieldComponents = new RandomAccessible[] { flatfield.getA(), flatfield.getB() }, adjustedFlatfieldComponents = new RandomAccessible[ 2 ];
				for ( int i = 0; i < flatfieldComponents.length; ++i )
				{
					final RandomAccessibleInterval< U > flatfieldComponentInterval = Views.interval( flatfieldComponents[ i ], new FinalInterval( tile.getSize() ) );
					final RandomAccessible< U > extendedFlatfieldComponent = Views.extendBorder( flatfieldComponentInterval );
					final RealRandomAccessible< U > interpolatedFlatfieldComponent = Views.interpolate( extendedFlatfieldComponent, new NLinearInterpolatorFactory<>() );
					final RandomAccessible< U > rasteredInterpolatedFlatfieldComponent = Views.raster( RealViews.affine( interpolatedFlatfieldComponent, translation ) );
					adjustedFlatfieldComponents[ i ] = Views.interval( rasteredInterpolatedFlatfieldComponent, intersectionIntervalInTargetInterval );
				}
				final RandomAccessiblePair< U, U > adjustedFlatfield = new RandomAccessiblePair<>( adjustedFlatfieldComponents[ 0 ], adjustedFlatfieldComponents[ 1 ] );
				final FlatfieldCorrectedRandomAccessible< R, U > flatfieldCorrectedTile = new FlatfieldCorrectedRandomAccessible<>( interpolatedTileInterval, adjustedFlatfield );
				final RandomAccessibleInterval< U > flatfieldCorrectedInterval = Views.interval( flatfieldCorrectedTile, intersectionIntervalInTargetInterval );
				sourceInterval = ( RandomAccessibleInterval ) Converters.convert( flatfieldCorrectedInterval, new RealFloatConverter<>(), new FloatType() );
			}
			else
			{
				sourceInterval = interpolatedTileInterval;
			}

			final RandomAccessibleInterval< FloatType > weightsInterval = Views.interval( weights, intersectionIntervalInTargetInterval ) ;
			final RandomAccessibleInterval< FloatType > valuesInterval = Views.interval( values, intersectionIntervalInTargetInterval ) ;
			final RandomAccessibleInterval< Set< Integer > > tileIndexesInterval = tileIndexes != null ? Views.interval( tileIndexes, intersectionIntervalInTargetInterval ) : null;

			final Cursor< R > sourceCursor = Views.flatIterable( sourceInterval ).localizingCursor();
			final Cursor< FloatType > weightsCursor = Views.flatIterable( weightsInterval ).cursor();
			final Cursor< FloatType > valuesCursor = Views.flatIterable( valuesInterval ).cursor();
			final Cursor< Set< Integer > > tileIndexesCursor = tileIndexesInterval != null ? Views.flatIterable( tileIndexesInterval ).cursor() : null;

			final double[] position = new double[ sourceCursor.numDimensions() ];
			while ( sourceCursor.hasNext() || weightsCursor.hasNext() || valuesCursor.hasNext() || ( tileIndexesCursor != null && tileIndexesCursor.hasNext() ) )
			{
				final double value = sourceCursor.next().getRealDouble();

				sourceCursor.localize( position );
				for ( int d = 0; d < position.length; ++d )
					position[ d ] -= offset[ d ];
				final double weight = getBlendingWeight( position, tileDimensions, FRACTION_BLENDED );

				final FloatType weightAccum = weightsCursor.next();
				final FloatType valueAccum = valuesCursor.next();
				weightAccum.setReal( weightAccum.getRealDouble() + weight );
				valueAccum.setReal( valueAccum.getRealDouble() + value * weight );

				if ( tileIndexesCursor != null )
					tileIndexesCursor.next().add( tile.getIndex() );
			}
		}

		final T fillType = dataType.createVariable();
		if ( backgroundValue != null)
			fillType.setReal( backgroundValue.doubleValue() );

		// initialize output image
		final ImagePlusImg< T, ? > out = new ImagePlusImgFactory< T >().create( Intervals.dimensionsAsLongArray( targetInterval ), dataType.createVariable() );
		final Cursor< FloatType > weightsCursor = Views.flatIterable( weights ).cursor();
		final Cursor< FloatType > valuesCursor = Views.flatIterable( values ).cursor();
		final Cursor< T > outCursor = Views.flatIterable( out ).cursor();
		while ( outCursor.hasNext() || weightsCursor.hasNext() || valuesCursor.hasNext() )
		{
			final double weight = weightsCursor.next().getRealDouble();
			final double value = valuesCursor.next().getRealDouble();
			outCursor.next().setReal( weight == 0 ? fillType.getRealDouble() : value / weight );
		}

		// retain only requested content within overlaps that corresponds to pairwise connections map
		if ( tileIndexes != null )
		{
			outCursor.reset();
			final Cursor< Set< Integer > > tileIndexesCursor = Views.flatIterable( tileIndexes ).cursor();
			while ( outCursor.hasNext() || tileIndexesCursor.hasNext() )
			{
				boolean retainPixel = false;
				final Set< Integer > tilesAtPoint = tileIndexesCursor.next();
				for ( final Integer testTileIndex : tilesAtPoint )
				{
					final Set< Integer > connectedTileIndexes = pairwiseConnectionsMap.get( testTileIndex );
					if ( connectedTileIndexes != null && !Collections.disjoint( tilesAtPoint, connectedTileIndexes ) )
					{
						retainPixel = true;
						break;
					}
				}

				outCursor.fwd();
				if ( !retainPixel )
					outCursor.get().set( fillType );
			}
		}

		return out;
	}
	private static double getBlendingWeight( final double[] location, final Dimensions dimensions, final double percentScaling )
	{
		// compute multiplicative distance to the respective borders [0...1]
		double minDistance = 1;

		for ( int dim = 0; dim < location.length; ++dim )
		{
			// the position in the image
			final double localImgPos = location[ dim ];

			// the distance to the border that is closer
			double value = Math.max( 1, Math.min( localImgPos, dimensions.dimension( dim ) - 1 - localImgPos ) );

			final float imgAreaBlend = Math.round( percentScaling * 0.5f * ( dimensions.dimension( dim ) - 1 ) );

			if ( value < imgAreaBlend )
				value = value / imgAreaBlend;
			else
				value = 1;

			minDistance *= value;
		}

		if ( minDistance == 1 )
			return 1;
		else if ( minDistance <= 0 )
			return 0.0000001;
		else
			return ( Math.cos( (1 - minDistance) * Math.PI ) + 1 ) / 2;
	}

	public static <
		T extends RealType< T > & NativeType< T >,
		U extends RealType< U > & NativeType< U >,
		R extends RealType< R > & NativeType< R > >
	ImagePlusImg< T, ? > fuseTilesWithinCellUsingMaxMinDistance(
			final DataProvider dataProvider,
			final List< TileInfo > tilesWithinCell,
			final Interval targetInterval,
			final T dataType,
			final Number backgroundValue,
			final RandomAccessiblePairNullable< U, U > flatfield,
			final Map< Integer, Set< Integer > > pairwiseConnectionsMap ) throws Exception
	{
		// initialize output image
		final ImagePlusImg< T, ? > out = new ImagePlusImgFactory< T >().create( Intervals.dimensionsAsLongArray( targetInterval ), dataType.createVariable() );

		final T fillType = dataType.createVariable();
		if ( backgroundValue != null)
			fillType.setReal( backgroundValue.doubleValue() );

		// fill with default value
		if ( backgroundValue != null )
			for ( final T outVal : out )
				outVal.set( fillType );

		// initialize helper image for hard-cut fusion strategy
		final RandomAccessibleInterval< FloatType > maxMinDistances = ArrayImgs.floats( Intervals.dimensionsAsLongArray( targetInterval ) );

		// initialize helper image for tile connections when exporting only overlaps
		final RandomAccessibleInterval< Set< Integer > > tileIndexes;
		if ( pairwiseConnectionsMap != null )
		{
			final List< Set< Integer > > tileIndexesList = new ArrayList<>( ( int ) out.size() );
			for ( int i = 0; i < out.size(); ++i )
				tileIndexesList.add( new HashSet<>() );
			tileIndexes = new ListImg<>( tileIndexesList, Intervals.dimensionsAsLongArray( targetInterval ) );
		}
		else
		{
			tileIndexes = null;
		}

		for ( final TileInfo tile : tilesWithinCell )
		{
			System.out.println( "Loading tile image " + tile.getFilePath() );

			final FinalRealInterval intersection = IntervalsNullable.intersectReal(
					new FinalRealInterval( tile.getPosition(), tile.getMax() ),
					targetInterval );

			if ( intersection == null )
				throw new IllegalArgumentException( "tilesWithinCell contains a tile that doesn't intersect with the target interval:\n" + "Tile " + tile.getIndex() + " at " + Arrays.toString( tile.getPosition() ) + " of size " + Arrays.toString( tile.getSize() ) + "\n" + "Output cell " + " at " + Arrays.toString( Intervals.minAsIntArray( targetInterval ) ) + " of size " + Arrays.toString( Intervals.dimensionsAsIntArray( targetInterval ) ) );

			final double[] offset = new double[ targetInterval.numDimensions() ];
			final long[] minIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			final long[] maxIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			for ( int d = 0; d < minIntersectionInTargetInterval.length; ++d )
			{
				offset[ d ] = tile.getPosition( d ) - targetInterval.min( d );
				minIntersectionInTargetInterval[ d ] = ( long ) Math.floor( intersection.realMin( d ) ) - targetInterval.min( d );
				maxIntersectionInTargetInterval[ d ] = ( long ) Math.ceil ( intersection.realMax( d ) ) - targetInterval.min( d );
			}
			final Interval intersectionIntervalInTargetInterval = new FinalInterval( minIntersectionInTargetInterval, maxIntersectionInTargetInterval );
			final Translation translation = new Translation( offset );

			final RandomAccessibleInterval< T > rawTile = TileLoader.loadTile( tile, dataProvider );
			final RandomAccessibleInterval< R > convertedTile = ( RandomAccessibleInterval ) Converters.convert( rawTile, new RealFloatConverter<>(), new FloatType() );
			final RandomAccessible< R > extendedTile = Views.extendBorder( convertedTile );
			final RealRandomAccessible< R > interpolatedTile = Views.interpolate( extendedTile, new NLinearInterpolatorFactory<>() );
			final RandomAccessible< R > rasteredInterpolatedTile = Views.raster( RealViews.affine( interpolatedTile, translation ) );
			final RandomAccessibleInterval< R > interpolatedTileInterval = Views.interval( rasteredInterpolatedTile, intersectionIntervalInTargetInterval );

			final RandomAccessibleInterval< R > sourceInterval;
			if ( flatfield != null )
			{
				final RandomAccessible< U >[] flatfieldComponents = new RandomAccessible[] { flatfield.getA(), flatfield.getB() }, adjustedFlatfieldComponents = new RandomAccessible[ 2 ];
				for ( int i = 0; i < flatfieldComponents.length; ++i )
				{
					final RandomAccessibleInterval< U > flatfieldComponentInterval = Views.interval( flatfieldComponents[ i ], new FinalInterval( tile.getSize() ) );
					final RandomAccessible< U > extendedFlatfieldComponent = Views.extendBorder( flatfieldComponentInterval );
					final RealRandomAccessible< U > interpolatedFlatfieldComponent = Views.interpolate( extendedFlatfieldComponent, new NLinearInterpolatorFactory<>() );
					final RandomAccessible< U > rasteredInterpolatedFlatfieldComponent = Views.raster( RealViews.affine( interpolatedFlatfieldComponent, translation ) );
					adjustedFlatfieldComponents[ i ] = Views.interval( rasteredInterpolatedFlatfieldComponent, intersectionIntervalInTargetInterval );
				}
				final RandomAccessiblePair< U, U > adjustedFlatfield = new RandomAccessiblePair<>( adjustedFlatfieldComponents[ 0 ], adjustedFlatfieldComponents[ 1 ] );
				final FlatfieldCorrectedRandomAccessible< R, U > flatfieldCorrectedTile = new FlatfieldCorrectedRandomAccessible<>( interpolatedTileInterval, adjustedFlatfield );
				final RandomAccessibleInterval< U > flatfieldCorrectedInterval = Views.interval( flatfieldCorrectedTile, intersectionIntervalInTargetInterval );
				sourceInterval = ( RandomAccessibleInterval ) Converters.convert( flatfieldCorrectedInterval, new RealFloatConverter<>(), new FloatType() );
			}
			else
			{
				sourceInterval = interpolatedTileInterval;
			}

			final RandomAccessibleInterval< T > outInterval = Views.interval( out, intersectionIntervalInTargetInterval ) ;
			final RandomAccessibleInterval< FloatType > maxMinDistanceInterval = Views.interval( maxMinDistances, intersectionIntervalInTargetInterval ) ;
			final RandomAccessibleInterval< Set< Integer > > tileIndexesInterval = tileIndexes != null ? Views.interval( tileIndexes, intersectionIntervalInTargetInterval ) : null;

			final Cursor< R > sourceCursor = Views.flatIterable( sourceInterval ).localizingCursor();
			final Cursor< T > outCursor = Views.flatIterable( outInterval ).cursor();
			final Cursor< FloatType > maxMinDistanceCursor = Views.flatIterable( maxMinDistanceInterval ).cursor();
			final Cursor< Set< Integer > > tileIndexesCursor = tileIndexesInterval != null ? Views.flatIterable( tileIndexesInterval ).cursor() : null;

			while ( sourceCursor.hasNext() || outCursor.hasNext() || maxMinDistanceCursor.hasNext() || ( tileIndexesCursor != null && tileIndexesCursor.hasNext() ) )
			{
				sourceCursor.fwd();
				outCursor.fwd();
				final FloatType maxMinDistance = maxMinDistanceCursor.next();
				double minDistance = Double.MAX_VALUE;
				for ( int d = 0; d < offset.length; ++d )
				{
					final double cursorPosition = sourceCursor.getDoublePosition( d );
					final double dx = Math.min(
							cursorPosition - offset[ d ],
							tile.getSize( d ) - 1 + offset[ d ] - cursorPosition );
					if ( dx < minDistance ) minDistance = dx;
				}
				if ( minDistance >= maxMinDistance.get() )
				{
					maxMinDistance.setReal( minDistance );
					outCursor.get().setReal( sourceCursor.get().getRealDouble() );
				}

				if ( tileIndexesCursor != null )
					tileIndexesCursor.next().add( tile.getIndex() );
			}
		}

		// retain only requested content within overlaps that corresponds to pairwise connections map
		if ( tileIndexes != null )
		{
			final Cursor< T > outCursor = Views.flatIterable( out ).cursor();
			final Cursor< Set< Integer > > tileIndexesCursor = Views.flatIterable( tileIndexes ).cursor();
			while ( outCursor.hasNext() || tileIndexesCursor.hasNext() )
			{
				boolean retainPixel = false;
				final Set< Integer > tilesAtPoint = tileIndexesCursor.next();
				for ( final Integer testTileIndex : tilesAtPoint )
				{
					final Set< Integer > connectedTileIndexes = pairwiseConnectionsMap.get( testTileIndex );
					if ( connectedTileIndexes != null && !Collections.disjoint( tilesAtPoint, connectedTileIndexes ) )
					{
						retainPixel = true;
						break;
					}
				}

				outCursor.fwd();
				if ( !retainPixel )
					outCursor.get().set( fillType );
			}
		}

		return out;
	}


	/**
	 * Performs the fusion of a collection of {@link TileInfo} objects within specified cell.
	 * It uses simple pixel copying strategy then downsamples the resulting image.
	 */
	public static < T extends RealType< T > & NativeType< T > > ImagePlusImg< T, ? > fuseTilesWithinCellSimpleWithDownsampling(
			final DataProvider dataProvider,
			final List< TileInfo > tiles,
			final TileInfo cell,
			final int[] downsampleFactors ) throws Exception
	{
		final ImageType imageType = Utils.getImageType( tiles );
		if ( imageType == null )
			throw new Exception( "Can't fuse images of different or unknown types" );

		cell.setType( imageType );
		final T type = ( T ) imageType.getType();
		final ImagePlusImg< T, ? > fusedImg = fuseSimple( dataProvider, tiles, cell, type );

		final long[] outDimensions = new long[ cell.numDimensions() ];
		for ( int d = 0; d < outDimensions.length; d++ )
			outDimensions[ d ] = cell.getSize( d ) / downsampleFactors[ d ];

		final ImagePlusImg< T, ? > downsampledImg = new ImagePlusImgFactory< T >().create( outDimensions, type.createVariable() );
		Downsample.downsample( fusedImg, downsampledImg, downsampleFactors );

		return downsampledImg;
	}


	@SuppressWarnings( "unchecked" )
	private static < T extends RealType< T > & NativeType< T > > ImagePlusImg< T, ? > fuseSimple(
			final DataProvider dataProvider,
			final List< TileInfo > tiles,
			final TileInfo cell,
			final T type ) throws Exception
	{
		//System.out.println( "Fusing tiles within cell #" + cell.getIndex() + " of size " + Arrays.toString( cell.getSize() )+"..." );

		// Create output image
		final Boundaries cellBoundaries = cell.getBoundaries();
		final ImagePlusImg< T, ? > out = new ImagePlusImgFactory< T >().create( cellBoundaries.getDimensions(), type.createVariable() );
		final RandomAccessibleInterval< T > cellImg = Views.translate( out, cellBoundaries.getMin() );

		for ( final TileInfo tile : tiles )
		{
			//System.out.println( "Loading tile image " + tile.getFilePath() );

			final Boundaries tileBoundaries = tile.getBoundaries();
			final FinalInterval intersection = IntervalsNullable.intersect( new FinalInterval( tileBoundaries.getMin(), tileBoundaries.getMax() ), cellImg );

			if ( intersection == null )
				throw new IllegalArgumentException( "tilesWithinCell contains a tile that doesn't intersect with the target interval" );

			final RandomAccessibleInterval< T > rawTile = TileLoader.loadTile( tile, dataProvider );
			final RandomAccessibleInterval< T > correctedDimTile = rawTile.numDimensions() < cell.numDimensions() ? Views.stack( rawTile ) : rawTile;
			final RealRandomAccessible< T > interpolatedTile = Views.interpolate( Views.extendBorder( correctedDimTile ), new NLinearInterpolatorFactory<>() );

			final AbstractTranslation translation = ( tile.numDimensions() == 3 ? new Translation3D( tile.getPosition() ) : new Translation2D( tile.getPosition() ) );
			final RandomAccessible< T > translatedInterpolatedTile = RealViews.affine( interpolatedTile, translation );

			final IterableInterval< T > tileSource = Views.flatIterable( Views.interval( translatedInterpolatedTile, intersection ) );
			final IterableInterval< T > cellBox = Views.flatIterable( Views.interval( cellImg, intersection ) );

			final Cursor< T > source = tileSource.cursor();
			final Cursor< T > target = cellBox.cursor();

			while ( source.hasNext() )
				target.next().set( source.next() );
		}

		return out;
	}


	// TODO: 'channel' version + virtual image loader was needed for the Zeiss dataset
	/*@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static < T extends RealType< T > & NativeType< T > > Img< T > fuseTilesWithinCellUsingMaxMinDistance(
			final List< TileInfo > tiles,
			final Interval targetInterval,
			final InterpolatorFactory< T, RandomAccessible< T > > interpolatorFactory,
			final int channel ) throws Exception
	{
		final ImageType imageType = Utils.getImageType( tiles );
		if ( imageType == null )
			throw new Exception( "Can't fuse images of different or unknown types" );

		final ArrayImg< T, ? > out = new ArrayImgFactory< T >().create(
				Intervals.dimensionsAsLongArray( targetInterval ),
				( T )imageType.getType().createVariable() );
		final ArrayImg< DoubleType, DoubleArray > maxMinDistances = ArrayImgs.doubles(
				Intervals.dimensionsAsLongArray( targetInterval ) );

		for ( final TileInfo tile : tiles )
		{
			System.out.println( "Loading tile image " + tile.getFilePath() );

			final ImagePlus imp = ImageImporter.openImage( tile.getFilePath() );
			Utils.workaroundImagePlusNSlices( imp );

			final FinalRealInterval intersection = intersectReal(
					new FinalRealInterval( tile.getPosition(), tile.getMax() ),
					targetInterval );

			final double[] offset = new double[ targetInterval.numDimensions() ];
			final Translation translation = new Translation( targetInterval.numDimensions() );
			final long[] minIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			final long[] maxIntersectionInTargetInterval = new long[ targetInterval.numDimensions() ];
			for ( int d = 0; d < minIntersectionInTargetInterval.length; ++d )
			{
				final double shiftInTargetInterval = intersection.realMin( d ) - targetInterval.min( d );
				minIntersectionInTargetInterval[ d ] = ( long )Math.floor( shiftInTargetInterval );
				maxIntersectionInTargetInterval[ d ] = ( long )Math.min( Math.ceil( intersection.realMax( d ) - targetInterval.min( d ) ), targetInterval.max( d ) );
				offset[ d ] = tile.getPosition( d ) - targetInterval.min( d );
				translation.set( offset[ d ], d );
			}

			// TODO: handle other data types
			final VirtualStackImageLoader< T, ?, ? > loader = ( VirtualStackImageLoader ) VirtualStackImageLoader.createUnsignedShortInstance( imp );
			final RandomAccessibleInterval< T > inputRai = loader.getSetupImgLoader( channel ).getImage( 0 );

			final RealRandomAccessible< T > interpolatedTile = Views.interpolate( Views.extendBorder( inputRai ), interpolatorFactory );
			final RandomAccessible< T > rasteredInterpolatedTile = Views.raster( RealViews.affine( interpolatedTile, translation ) );

			final IterableInterval< T > sourceInterval =
					Views.flatIterable(
							Views.interval(
									rasteredInterpolatedTile,
									minIntersectionInTargetInterval,
									maxIntersectionInTargetInterval ) );
			final IterableInterval< T > outInterval =
					Views.flatIterable(
							Views.interval(
									out,
									minIntersectionInTargetInterval,
									maxIntersectionInTargetInterval ) );
			final IterableInterval< DoubleType > maxMinDistancesInterval =
					Views.flatIterable(
							Views.interval(
									maxMinDistances,
									minIntersectionInTargetInterval,
									maxIntersectionInTargetInterval ) );

			final Cursor< T > sourceCursor = sourceInterval.localizingCursor();
			final Cursor< T > outCursor = outInterval.cursor();
			final Cursor< DoubleType > maxMinDistanceCursor = maxMinDistancesInterval.cursor();

			while ( sourceCursor.hasNext() )
			{
				sourceCursor.fwd();
				outCursor.fwd();
				final DoubleType maxMinDistance = maxMinDistanceCursor.next();
				double minDistance = Double.MAX_VALUE;
				for ( int d = 0; d < offset.length; ++d )
				{
					final double cursorPosition = sourceCursor.getDoublePosition( d );
					final double dx = Math.min(
							cursorPosition - offset[ d ],
							tile.getSize( d ) - 1 + offset[ d ] - cursorPosition );
					if ( dx < minDistance ) minDistance = dx;
				}
				if ( minDistance >= maxMinDistance.get() )
				{
					maxMinDistance.set( minDistance );
					outCursor.get().set( sourceCursor.get() );
				}
			}
		}

		return out;
	}*/
}
