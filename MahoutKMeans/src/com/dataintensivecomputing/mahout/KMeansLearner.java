package com.dataintensivecomputing.mahout;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.log4j.spi.Configurator;
import org.apache.mahout.clustering.Cluster;
import org.apache.mahout.clustering.classify.WeightedPropertyVectorWritable;
import org.apache.mahout.clustering.classify.WeightedVectorWritable;
import org.apache.mahout.clustering.conversion.InputDriver;
import org.apache.mahout.common.distance.DistanceMeasure;
import org.apache.mahout.common.distance.EuclideanDistanceMeasure;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;

import com.dataintensivecomputing.confighandler.*;

import org.apache.mahout.clustering.kmeans.KMeansDriver;
import org.apache.mahout.clustering.kmeans.RandomSeedGenerator;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.RandomAccessSparseVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.utils.clustering.ClusterDumper;
public class KMeansLearner extends Configured{
	private String inputPath = "";
	private String outputPath = "";
	private int k = 0;
	private ConfigHandler config;
	private boolean  createSequence=false;
	private boolean createInitialCluster=false;
	private boolean runSequential = false;
	private int maxIterations = 0;
	private String initialClusterPath = "";
	public KMeansLearner(String configPath)  {
		// TODO Auto-generated constructor stub
		config = ConfigHandler.getInstance() ;
		config.setProperties(configPath);	
		inputPath = ConfigHandler.prop.getProperty("input");
		outputPath = config.prop.getProperty("output");
		if(config.prop.getProperty("runSequential").equals("1"))
		{
			runSequential = true;
		}
		else
		{
			runSequential = false;
		}
		if(config.prop.getProperty("createSequence").equals("1"))
		{
			createSequence = true;
		}
		
		if(config.prop.getProperty("createInitialCluster").equals("1"))
		{
			createInitialCluster = true;
		}
		
		k = Integer.valueOf(config.prop.getProperty("k"));
		maxIterations = Integer.valueOf(config.prop.getProperty("maxIterations"));
		
		if(config.prop.getProperty("initialClusterPath").equals("-"))
		{
			initialClusterPath = "";
		}
		else
		{
			initialClusterPath = config.prop.getProperty("initialClusterPath");
		}

	}
	
	public void TransformVectorsToSequence(Configuration conf,
			String inputPath, String outputPath) throws IOException {
		
		
		FileSystem fs = FileSystem.get(conf);
		Path inPath = new Path(inputPath);
		Path outPath = new Path(outputPath);
		
		if(!fs.exists(outPath))
		{
			fs.mkdirs(outPath);
		}
		fs.delete(outPath, true);
		
		Path siftSeq = new Path(outputPath + "sift_sequence");
		
		SequenceFile.Writer writer = SequenceFile.createWriter(fs,conf,siftSeq,Text.class,VectorWritable.class);

		Text key = new Text();
		VectorWritable value = new VectorWritable();

		FSDataInputStream fdstream = fs.open(inPath);
		BufferedReader reader = new BufferedReader(new InputStreamReader(
				fdstream));

		String line = "";
		int id = 0;
		while ((line = reader.readLine()) != null) {
			StringTokenizer token = new StringTokenizer(line);
			int numTokens = token.countTokens();
			NamedVector vec;
			//RandomAccessSparseVector vec = new RandomAccessSparseVector();
			//Vector vec = new RandomAccessSparseVector(60);
			double[] features = new double[numTokens];
			for (int i = 0; i < numTokens; ++i)
			{
				features[i] = Double.parseDouble(token.nextToken());
				
				
			}
			key.set(String.valueOf(++id));
			vec = new NamedVector(new DenseVector(features),key.toString());
			
			value.set(vec);
			writer.append(key, value);
			
		}
		writer.close();
	}
	
	public void KMeansHandler() throws IOException, InterruptedException, ClassNotFoundException
	{
		//read config file
		Configuration conf = new Configuration();
		conf.addResource(new Path("/usr/local/hadoop/conf/core-site.xml"));
		conf.addResource(new Path("/usr/local/hadoop/conf/mapred-site.xml"));
		conf.addResource(new Path("/usr/local/hadoop/conf/hdfs-site.xml"));
		
	 	 
	    
		DistanceMeasure measure = new EuclideanDistanceMeasure();
		Path input = new Path(inputPath);
		String sequencePath = ConfigHandler.prop.getProperty("sequencePath");
		
		Path sequence = new Path(sequencePath);
		//FileSystem.get(conf).delete(sequence, true);
		
		if(createSequence)
		{
			TransformVectorsToSequence(conf, inputPath, sequencePath);
		}
			
		
		//InputDriver.runJob(input, sequence, "org.apache.mahout.math.RandomAccessSparseVector");
		
		
		Path output = new Path(outputPath);
		Path clusters = null;
		 
		if(createInitialCluster)
		{
			if(!FileSystem.get(conf).exists(output))
			{
				FileSystem.get(conf).mkdirs(output);
			}
		
			FileSystem.get(conf).delete(output, true);
		
			clusters = new Path(output, Cluster.INITIAL_CLUSTERS_DIR);
		
			clusters = RandomSeedGenerator.buildRandom(conf, sequence, clusters, k, measure);
		}
		
		Path existingCluster = null;
		
		if(createInitialCluster)
		{
			existingCluster = clusters;
			
		}
		else if(!initialClusterPath.equals(""))
		{
			existingCluster = new Path(initialClusterPath);
		}
		//called when randomseed already called before
		else
		{
			existingCluster = new Path(output + "/" + Cluster.INITIAL_CLUSTERS_DIR + "/" + "part-randomSeed");
		}
		
		if(!FileSystem.get(conf).exists(existingCluster))
		{
			System.out.println("Initial Cluster not found");
			return;
		}
	    // Kmeans clustering
	    Path siftSeq = new Path(sequencePath + "sift_sequence");
	    double convergenceDelta = 1e-3;
	 	int maxIterations = this.maxIterations;
	 	System.out.println(siftSeq.getName() + " " + existingCluster.getName() + " " + output.getName());
	 	if(!runSequential)
	 	{
	 		KMeansDriver.run(conf, siftSeq, existingCluster, output, convergenceDelta, maxIterations, true, 0.0, false);
	 	}
	 	else
	 	{
	 		KMeansDriver.run(conf, siftSeq, existingCluster, output, convergenceDelta, maxIterations, true, 0.0, true);
	 	}
	 	
	 	

	 	/*
	 	// run ClusterDumper - gives out of memory exception
	 	ClusterDumper clusterDumper = new ClusterDumper(new Path(output, "clusters-*-final"), new Path(output,
	 	        "clusteredPoints"));
	 	  String textoutput = "HumanReadableClusters";
		    FSDataOutputStream fsout = FileSystem.get(conf).create(new Path(output, textoutput));
		    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(fsout));
		    Map<Integer, List<WeightedPropertyVectorWritable>> clusterMap = clusterDumper.getClusterIdToPoints();
		    for (Entry<Integer, List<WeightedPropertyVectorWritable>> entry : clusterMap.entrySet()) {
		    	writer.write(entry.toString() + "\n");
		    }
		    writer.close();
		 */
	}

	/**
	 * @param args
	 * @throws ClassNotFoundException 
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException, InterruptedException, ClassNotFoundException {
		// TODO Auto-generated method stub
		KMeansLearner kl = new KMeansLearner(args[0]);
		kl.KMeansHandler();
	}

}
