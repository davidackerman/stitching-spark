package org.janelia.stitching.analysis;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.janelia.dataaccess.DataProvider;
import org.janelia.dataaccess.DataProviderFactory;
import org.janelia.stitching.*;

import net.imglib2.util.Pair;

public class FilterPairwiseShifts
{
	// TODO: Add flexibility. Currently hardcoded for Z
	public static void main( final String[] args ) throws Exception
	{
		final DataProvider dataProvider = DataProviderFactory.createFSDataProvider();

		final List< SerializablePairWiseStitchingResult > shifts = TileInfoJSONProvider.loadPairwiseShifts( dataProvider.getJsonReader( args[ 0 ] ) );

		final TileInfo[] tiles = Utils.createTilesMap( shifts, true ).values().toArray( new TileInfo[ 0 ] );
		final AxisMapping axisMapping = new AxisMapping( args[ 1 ] );
		final Map< Integer, int[] > tileIndexToCoordinates = new HashMap<>();
		for ( final Pair< TileInfo, int[] > tileCoordinates : Utils.getTilesCoordinates( tiles, axisMapping ) )
			tileIndexToCoordinates.put( tileCoordinates.getA().getIndex(), tileCoordinates.getB() );

		final int sizeBefore = shifts.size();
		for ( final Iterator< SerializablePairWiseStitchingResult > it = shifts.iterator(); it.hasNext(); )
		{
			final SerializablePairWiseStitchingResult shift = it.next();
			if ( tileIndexToCoordinates.get( shift.getTilePair().getA().getIndex() )[ 2 ] - tileIndexToCoordinates.get( shift.getTilePair().getB().getIndex() )[ 2 ] != 0 )
				it.remove();
		}
		final int sizeAfter = shifts.size();

		System.out.println( "Size before = " + sizeBefore + ", size after = " + sizeAfter );

		TileInfoJSONProvider.savePairwiseShifts( shifts, dataProvider.getJsonWriter( Utils.addFilenameSuffix( args[ 0 ], "_without_z" ) ) );
	}
}
