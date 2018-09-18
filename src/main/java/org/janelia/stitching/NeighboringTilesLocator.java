package org.janelia.stitching;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
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
import net.imglib2.util.Intervals;

public class NeighboringTilesLocator implements Serializable
{
	private static final long serialVersionUID = 3966655006478467424L;

	private final double[] searchWindowSize;
	private final int subdivisionGridSize;
	private final KDTree< TileInfo > kdTree;

	public NeighboringTilesLocator(
			final TileInfo[] tiles,
			final double[] searchWindowSize,
			final int subdivisionGridSize )
	{
		this.searchWindowSize = searchWindowSize;
		this.subdivisionGridSize = subdivisionGridSize;

		final List< TileInfo > tilesWithStitchedTransform = new ArrayList<>();
		for ( final TileInfo tile : tiles )
			if ( tile.getTransform() != null )
				tilesWithStitchedTransform.add( tile );

		final List< RealLocalizable > stageSubsetPositions = new ArrayList<>();
		for ( final TileInfo tileWithStitchedTransform : tilesWithStitchedTransform )
			stageSubsetPositions.add( new RealPoint( tileWithStitchedTransform.getStagePosition() ) );

		// build a search tree to be able to look up neighboring tiles by stage position
		kdTree = new KDTree<>( tilesWithStitchedTransform, stageSubsetPositions );
	}

	public double[] getSearchWindowSize()
	{
		return searchWindowSize;
	}

	public int getSubdivisionGridSize()
	{
		return subdivisionGridSize;
	}

	public Set< SubTile > findSubTilesWithinWindow( final SubTile subTile )
	{
		return findSubTilesWithinWindow( subTile, getSearchWindowInterval( subTile.getFullTile() ) );
	}

	public Set< SubTile > findSubTilesWithinWindow( final SubTile subTile, final Interval window )
	{
		return getNeighboringSubTiles( subTile, findTilesWithinWindow( window ) );
	}

	private Set< SubTile > getNeighboringSubTiles( final SubTile subTile, final Set< TileInfo > neighboringTiles )
	{
		final Set< SubTile > neighboringSubTiles = new HashSet<>();
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

	public static double[] getSearchWindowSize( final long[] tileSize, final int[] searchWindowTileSize )
	{
		final double[] searchWindowSize = new double[ tileSize.length ];
		for ( int d = 0; d < tileSize.length; ++d )
			searchWindowSize[ d ] = tileSize[ d ] * searchWindowTileSize[ d ];
		return searchWindowSize;
	}

	public Interval getSearchWindowInterval( final TileInfo tile )
	{
		return getSearchWindowInterval( new RealPoint( tile.getStagePosition() ) );
	}

	public Interval getSearchWindowInterval( final RealPoint point )
	{
		final long[] searchWindowMin = new long[ searchWindowSize.length ], searchWindowMax = new long[ searchWindowSize.length ];
		for ( int d = 0; d < searchWindowSize.length; ++d )
		{
			searchWindowMin[ d ] = ( long ) Math.floor( point.getDoublePosition( d ) - searchWindowSize[ d ] / 2 );
			searchWindowMax[ d ] = ( long ) Math.ceil ( point.getDoublePosition( d ) + searchWindowSize[ d ] / 2 );
		}
		return new FinalInterval( searchWindowMin, searchWindowMax );
	}
}