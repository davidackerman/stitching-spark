package org.janelia.histogram;

import java.io.Serializable;

import net.imglib2.type.Type;
import net.imglib2.type.operators.Add;
import net.imglib2.type.operators.MulFloatingPoint;
import net.imglib2.util.Util;

public class Histogram implements Type< Histogram >, Add< Histogram >, MulFloatingPoint, Serializable
{
	private static final long serialVersionUID = -3130834243396947444L;

	private double[] histogram;
	private double minValue, maxValue, binWidth;
	private double quantityTotal, quantityLessThanMin, quantityGreaterThanMax;

	public Histogram( final double minValue, final double maxValue, final int bins )
	{
		assert minValue < maxValue;
		this.histogram = new double[ bins ];
		this.minValue = minValue;
		this.maxValue = maxValue;
		this.binWidth = ( maxValue - minValue ) / bins;
	}

	public int getNumBins() { return histogram.length; }
	public double getMinValue() { return minValue; }
	public double getMaxValue() { return maxValue; }
	public double getBinWidth() { return binWidth; }
	public double getQuantityTotal() { return quantityTotal; }
	public double getQuantityLessThanMin() { return quantityLessThanMin; }
	public double getQuantityGreaterThanMax() { return quantityGreaterThanMax; }

	public double get( final int bin )
	{
		return histogram[ bin ];
	}

	public double[] getHistogram()
	{
		return histogram.clone();
	}

	public double getBinValue( final int bin )
	{
		return minValue + ( bin + 0.5 ) * binWidth - 0.5;
	}

	public void put( final double value )
	{
		put( value, 1 );
	}
	public void put( final double value, final double quantity )
	{
		final int bin;
		if ( value < minValue )
		{
			bin = 0;
			quantityLessThanMin += quantity;
		}
		else if ( value >= maxValue )
		{
			bin = histogram.length - 1;
			quantityGreaterThanMax += quantity;
		}
		else
		{
			bin = ( int ) Math.floor( ( value - minValue ) / binWidth );
		}
		histogram[ bin ] += quantity;
		quantityTotal += quantity;
	}

	@Override
	public void add( final Histogram other )
	{
		for ( int bin = 0; bin < getNumBins(); ++bin )
			histogram[ bin ] += other.get( bin );

		quantityTotal += other.getQuantityTotal();
		quantityLessThanMin += other.getQuantityLessThanMin();
		quantityGreaterThanMax += other.getQuantityGreaterThanMax();
	}

	public void average( final long numHistograms )
	{
		mul( 1. / numHistograms );
	}

	@Override
	public void mul( final float ratio )
	{
		mul( ( double ) ratio );
	}

	@Override
	public void mul( final double ratio )
	{
		for ( int bin = 0; bin < getNumBins(); ++bin )
			histogram[ bin ] *= ratio;

		quantityTotal *= ratio;
		quantityLessThanMin *= ratio;
		quantityGreaterThanMax *= ratio;
	}

	@Override
	public boolean valueEquals( final Histogram other )
	{
		if ( histogram.length != other.histogram.length )
			return false;

		if ( !Util.isApproxEqual( minValue, other.minValue, 1e-9 ) || !Util.isApproxEqual( maxValue, other.maxValue, 1e-9 ) )
			return false;

		if ( !Util.isApproxEqual( quantityLessThanMin, other.quantityLessThanMin, 1e-9 ) || !Util.isApproxEqual( quantityGreaterThanMax, other.quantityGreaterThanMax, 1e-9 ) )
			return false;

		if ( !Util.isApproxEqual( quantityTotal, other.quantityTotal, 1e-9 ) )
			return false;

		for ( int bin = 0; bin < histogram.length; ++bin )
			if ( !Util.isApproxEqual( histogram[ bin ], other.histogram[ bin ], 1e-9 ) )
				return false;

		return true;
	}

	@Override
	public Histogram createVariable()
	{
		return new Histogram( minValue, maxValue, histogram.length );
	}

	@Override
	public Histogram copy()
	{
		final Histogram copy = new Histogram( minValue, maxValue, histogram.length );
		copy.set( this );
		return copy;
	}

	@Override
	public void set( final Histogram other )
	{
		histogram = other.histogram.clone();
		minValue = other.minValue;
		maxValue = other.maxValue;
		binWidth = other.binWidth;
		quantityTotal = other.quantityTotal;
		quantityLessThanMin = other.quantityLessThanMin;
		quantityGreaterThanMax = other.quantityGreaterThanMax;
	}
}
