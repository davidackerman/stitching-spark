package org.janelia.stitching.analysis;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.stitching.SerializablePairWiseStitchingResult;
import org.janelia.stitching.TileInfo;
import org.janelia.stitching.TileInfoJSONProvider;
import org.janelia.stitching.Utils;
import org.janelia.util.Conversions;

import mpicbg.models.Point;

public class EstimateErrorsForTileConfiguration
{
	public static void main( final String[] args ) throws Exception
	{
		final String inputTilesPath = args[ 0 ], stitchedTilesPath = args[ 1 ], pairwisePath = args[ 2 ];
		final DataProvider dataProvider = DataProviderFactory.createFSDataProvider();
		final TileInfo[] inputTiles = TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( URI.create( inputTilesPath ) ) );
		final TileInfo[] stitchedTiles = TileInfoJSONProvider.loadTilesConfiguration( dataProvider.getJsonReader( URI.create( stitchedTilesPath ) ) );
		final List< SerializablePairWiseStitchingResult > pairwise = TileInfoJSONProvider.loadPairwiseShifts( dataProvider.getJsonReader( URI.create( pairwisePath ) ) );

		System.out.println( "tiles total=" + inputTiles.length + ", tiles retained=" + stitchedTiles.length );

		final Map< Integer, TileInfo > stitchedTilesMap = Utils.createTilesMap( stitchedTiles );
		final Map< Integer, Map< Integer, Double > > pairwiseErrors = new TreeMap<>();
		int pairsRetained = 0;
		for ( final SerializablePairWiseStitchingResult pair : pairwise )
		{
			if ( !pair.getIsValidOverlap() )
				continue;

			final TileInfo fixedTile = stitchedTilesMap.get( pair.getTilePair().getA().getIndex() );
			final TileInfo movingTile = stitchedTilesMap.get( pair.getTilePair().getB().getIndex() );

			if ( fixedTile != null && movingTile != null )
			{
				++pairsRetained;

				final double[] pairwiseShift = Conversions.toDoubleArray( pair.getOffset() );
				final double[] stitchedOffset = new double[ pairwiseShift.length ];
				for ( int d = 0; d < stitchedOffset.length; ++d )
					stitchedOffset[ d ] = movingTile.getPosition( d ) - fixedTile.getPosition( d );

				final double distance = Point.distance( new Point( pairwiseShift ), new Point( stitchedOffset ) );

				final int[] indexes = new int[] { fixedTile.getIndex(), movingTile.getIndex() };
				for ( int i = 0; i < 2; ++i )
				{
					if ( !pairwiseErrors.containsKey( indexes[ i ] ) )
						pairwiseErrors.put( indexes[ i ], new TreeMap<>() );
					pairwiseErrors.get( indexes[ i ] ).put( indexes[ ( i + 1 ) % 2 ], distance );
				}
			}
		}

		final Map< Integer, Double > tileErrors = new TreeMap<>();
		for ( final TileInfo stitchedTile : stitchedTiles )
		{
			double tileDistance = 0.0;
			final Map< Integer, Double > pairwiseErrorsForTile = pairwiseErrors.get( stitchedTile.getIndex() );
			for ( final Double distance : pairwiseErrorsForTile.values() )
				tileDistance += distance;
			tileDistance /= pairwiseErrorsForTile.size();
			tileErrors.put( stitchedTile.getIndex(), tileDistance );
		}

		double minError = Double.POSITIVE_INFINITY, maxError = Double.NEGATIVE_INFINITY, avgError = 0;
		for ( final Double tileError : tileErrors.values() )
		{
			minError = Math.min( tileError, minError );
			maxError = Math.max( tileError, maxError );
			avgError += tileError;
		}
		avgError /= tileErrors.size();

		System.out.println( "pairs total=" + pairwise.size() + ", pairs retained=" + pairsRetained );
		System.out.println();
		System.out.println( String.format( "min.error=%.2f, max.error=%.2f, avg.error=%.2f", minError, maxError, avgError ) );
	}
}
