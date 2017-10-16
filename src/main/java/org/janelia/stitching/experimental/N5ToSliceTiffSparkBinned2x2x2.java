package org.janelia.stitching.experimental;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.bdv.N5ExportMetadata;
import org.janelia.saalfeldlab.n5.spark.N5RemoveSpark;

public class N5ToSliceTiffSparkBinned2x2x2
{
	public static void main( final String[] args ) throws Exception
	{
		int lastArg = 0;
		final String n5InputPath = args[ lastArg++ ];
		final String outputBasePath = args[ lastArg++ ];

		final Integer requestedChannel;
		{
			if ( args.length > lastArg )
			{
				Integer requestedChannelParsed;
				try
				{
					requestedChannelParsed = Integer.parseInt( args[ lastArg ] );
					lastArg++;
				}
				catch ( final NumberFormatException e )
				{
					requestedChannelParsed = null;
				}
				requestedChannel = requestedChannelParsed;
			}
			else
			{
				requestedChannel = null;
			}
		}

		final int scaleLevel = 1;
		final N5ExportMetadata exportMetadata = new N5ExportMetadata( n5InputPath );
		final double[][] scales = exportMetadata.getScales( 0 );
		if ( Math.round( scales[ 1 ][ 0 ] ) != 2 || Math.round( scales[ 1 ][ 1 ] ) != 2 || Math.round( scales[ 1 ][ 2 ] ) != 1 )
			throw new Exception( "Expected 2x2x1 binned scale level in order to downsample in Z, can't find it" );

		System.out.println( "Using scale level " + scaleLevel + " to downsample in Z to get 2x2x2 binned version" );

		System.out.println( requestedChannel != null ? "Processing channel " + requestedChannel : "Processing all channels" );

		final String n5OutputPath = Paths.get( outputBasePath, "tmp.n5" ).toString();
		final String outputDataset = "binned-2x2x2";
		final String outFolder = "slice-tiff-binned-2x2x2";

		try ( final JavaSparkContext sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "ConvertN5ToSliceTIFFBinned2x2x2" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
				) )
		{
			for ( int channel = 0; channel < exportMetadata.getNumChannels(); ++channel )
			{
				if ( requestedChannel != null && channel != requestedChannel.intValue() )
					continue;

				final String outputChannelPath = Paths.get( outputBasePath, outFolder, "ch" + channel ).toString();
				new File( outputChannelPath ).mkdirs();

				downsample(
						sparkContext,
						n5InputPath,
						N5ExportMetadata.getScaleLevelDatasetPath( channel, scaleLevel ),
						new int[] { 1, 1, 2 },
						n5OutputPath,
						outputDataset
					);

//				N5SliceTiffConverter.convertToSliceTiff(
//						sparkContext,
//						n5OutputPath,
//						outputDataset,
//						outputChannelPath,
//						"ch" + channel + "_",
//						TiffUtils.TiffCompression.LZW
//					);

				N5RemoveSpark.remove( sparkContext, n5OutputPath, outputDataset );
				Files.delete( Paths.get( n5OutputPath ) );
			}
		}
	}

	private static void downsample(
			final JavaSparkContext sparkContext,
			final String inputBasePath,
			final String inputDatasetPath,
			final int[] downsamplingFactors,
			final String outputBasePath,
			final String outputDatasetPath) throws IOException
	{
		final N5Reader n5Input = N5.openFSReader( inputBasePath );
		final DatasetAttributes sourceAttributes = n5Input.getDatasetAttributes( inputDatasetPath );

		final long[] downsampledDimensions = sourceAttributes.getDimensions().clone();
		for ( int d = 0; d < downsampledDimensions.length; ++d )
			downsampledDimensions[ d ] /= downsamplingFactors[ d ];

		N5.openFSWriter( outputBasePath ).createDataset(
				outputDatasetPath,
				downsampledDimensions,
				sourceAttributes.getBlockSize().clone(),
				sourceAttributes.getDataType(),
				sourceAttributes.getCompressionType()
			);

//		N5DownsamplingSpark.downsample( sparkContext, inputBasePath, inputDatasetPath, outputBasePath, outputDatasetPath );
	}
}
