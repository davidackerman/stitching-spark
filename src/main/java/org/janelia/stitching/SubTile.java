package org.janelia.stitching;

import net.imglib2.AbstractInterval;
import net.imglib2.Interval;

/**
 * Represents a subtile within a full tile.
 *
 * @author Igor Pisarev
 */

public class SubTile extends AbstractInterval
{
	private final TileInfo fullTile;
	private Integer index;

	public SubTile( final TileInfo fullTile )
	{
		super( fullTile.numDimensions() );
		this.fullTile = fullTile;
	}

	public SubTile( final TileInfo fullTile, final Interval interval )
	{
		super( interval );
		this.fullTile = fullTile;
	}

	public void set( final int[] min, final int[] max )
	{
		for ( int d = 0; d < numDimensions(); ++d )
		{
			this.min[ d ] = min[ d ];
			this.max[ d ] = max[ d ];
		}
	}

	public Integer getIndex()
	{
		return index;
	}

	public void setIndex( final Integer index )
	{
		this.index = index;
	}

	public TileInfo getFullTile()
	{
		return fullTile;
	}
}