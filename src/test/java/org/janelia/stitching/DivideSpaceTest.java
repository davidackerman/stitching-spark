package org.janelia.stitching;

import java.util.List;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;

/**
 * @author Igor Pisarev
 */

public class DivideSpaceTest {

	@Test
	public void test1d() {
		final Interval space = new FinalInterval( new long[] { 10 }, new long[] { 40 } );
		final List< TileInfo > res = TileOperations.divideSpaceBySize( space, 14 );
		Assert.assertEquals( 3, res.size() );

		for ( int i = 0; i < res.size(); i++ )
			Assert.assertEquals( i, (int)res.get(i).getIndex() );

		Assert.assertEquals( 10, res.get( 0 ).getPosition( 0 ), 0.f );	Assert.assertEquals( 14, res.get( 0 ).getSize( 0 ) );
		Assert.assertEquals( 24, res.get( 1 ).getPosition( 0 ), 0.f );	Assert.assertEquals( 14, res.get( 1 ).getSize( 0 ) );
		Assert.assertEquals( 38, res.get( 2 ).getPosition( 0 ), 0.f );	Assert.assertEquals( 3,  res.get( 2 ).getSize( 0 ) );
	}

	@Test
	public void randomTestBySize()
	{
		final Random rnd = new Random();

		final int dim = rnd.nextInt(5) + 1;
		final long[] min = new long[ dim ], max = new long[ dim ];
		for ( int d = 0; d < dim; d++ ) {
			min[ d ] = rnd.nextInt( 1000 ) - 500;
			max[ d ] = rnd.nextInt( 1000 ) + min[ d ];
		}
		final Interval space = new FinalInterval( min, max );

		final int subregionSize = rnd.nextInt( 100 ) + 50;
		int expectedCount = 1;
		for ( int d = 0; d < space.numDimensions(); d++ )
			expectedCount *= ( int ) Math.ceil( ( double ) space.dimension( d ) / subregionSize );

		final int actualCount = TileOperations.divideSpaceBySize( space, subregionSize).size();

		System.out.println( "[DivideSpaceTest] Random test by size:" );
		System.out.println( "Dim = " + space.numDimensions() );
		System.out.println( "Subregion size = " + subregionSize );
		System.out.println( "Expected subregions count = " + expectedCount );
		System.out.println( "Actual subregions count = " + actualCount );

		Assert.assertEquals( expectedCount, actualCount );
	}

	@Test
	public void randomTestByCount()
	{
		final Random rnd = new Random();

		final int dim = rnd.nextInt(5) + 1;
		final long[] min = new long[ dim ], max = new long[ dim ];
		for ( int d = 0; d < dim; d++ ) {
			min[ d ] = rnd.nextInt( 1000 ) - 500;
			max[ d ] = rnd.nextInt( 1000 ) + min[ d ];
		}
		final Interval space = new FinalInterval( min, max );

		int subregionCountPerDim = rnd.nextInt( 15 ) + 1;
		for ( int d = 0; d < space.numDimensions(); d++ )
			subregionCountPerDim = ( int ) Math.min( space.dimension( d ), subregionCountPerDim );

		final int expectedCount = ( int ) Math.pow( subregionCountPerDim, space.numDimensions() );

		final int actualCount = TileOperations.divideSpaceByCount( space, subregionCountPerDim ).size();

		System.out.println( "[DivideSpaceTest] Random test by count:" );
		System.out.println( "Dim = " + space.numDimensions() );
		System.out.println( "Subregion count per dimension = " + subregionCountPerDim );
		System.out.println( "Expected subregions count = " + expectedCount );
		System.out.println( "Actual subregions count = " + actualCount );

		Assert.assertEquals( expectedCount, actualCount );
	}
}
