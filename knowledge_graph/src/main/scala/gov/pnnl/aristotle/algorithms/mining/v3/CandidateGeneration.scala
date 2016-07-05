/**
 *
 * @author puro755
 * @dJul 5, 2016
 * @knowledge_graph
 */
package gov.pnnl.aristotle.algorithms.mining.v3

import org.apache.spark.graphx.Graph
import gov.pnnl.aristotle.algorithms.mining.datamodel.KGEdge
import gov.pnnl.aristotle.algorithms.mining.datamodel.KGNodeV2Flat
import java.io.PrintWriter
import gov.pnnl.aristotle.algorithms.mining.GraphProfiling
import org.apache.spark.graphx.VertexRDD
import gov.pnnl.aristotle.algorithms.mining.datamodel.PGNode
import org.apache.spark.rdd.RDD
import scalaz.Scalaz._
import org.apache.spark.SparkContext._
import gov.pnnl.aristotle.algorithms.mining.datamodel.PatternDependencyGraph
import gov.pnnl.aristotle.algorithms.mining.datamodel.PatternGraph
import org.apache.spark.graphx.EdgeContext
import org.apache.spark.SparkContext
import org.apache.spark.graphx.VertexId
import org.apache.spark.graphx.Edge

/**
 * @author puro755
 *
 */
class CandidateGeneration(val minSup: Int) extends Serializable{

  var TYPE: String = "rdf:type"
  var SUPPORT: Int = minSup
  var type_support: Int = 2
  var input_graph: Graph[KGNodeV2Flat, KGEdge] = null
  def init(graph: Graph[String, KGEdge], writerSG: PrintWriter, basetype: String,
    type_support: Int): CandidateGeneration = {

    /*
     * Get all the rdf:type dst node information on the source node
     */
    this.TYPE = basetype
    this.type_support = type_support
    println("***************support is " + SUPPORT)

    // Now we have the type information collected in the original graph
    val typedAugmentedGraph: Graph[(String, Map[String, Map[String, Int]]), KGEdge] = getTypedGraph(graph, writerSG)

    /*
     * Create RDD where Every vertex has all the 1 edge patterns it belongs to
     * Ex: Sumit: (person worksAt organizaion) , (person friendsWith person)
     * 
     * Read method comments
     * ....
     * ....
     * ....So in some sense, the methods' name is misleading. TODO: fix the name
     */
    val nonTypedVertexRDD: VertexRDD[Map[String, Long]] =
      getOneEdgePatterns(typedAugmentedGraph)
    //nonTypedVertexRDD.collect.foreach(f=>writerSG.println("non-type"+f.toString))
    val updatedGraph: Graph[KGNodeV2Flat, KGEdge] =
      typedAugmentedGraph.outerJoinVertices(nonTypedVertexRDD) {
        case (id, (label, something), Some(nbr)) => new KGNodeV2Flat(label, nbr, List.empty)
        case (id, (label, something), None) => new KGNodeV2Flat(label, Map(), List.empty)
      }
    /*
     *  Filter : all nodes that don't have even a single pattern
     */
    writerSG.flush()
    /*
     * update sink nodes and push source pattern at sink node with no destination information
     * so that when we compute support of that pattern, it will not change the value.
     * get all nodes which are destination of any edge
     */
    val updateGraph_withsink = updateGraphWithSinkV2(updatedGraph)
    val result = null
    this.input_graph = get_Frequent_SubgraphV2Flat(updateGraph_withsink, result, SUPPORT)
    return this
  }

  def getTypedGraph(graph: Graph[String, KGEdge],
    writerSG: PrintWriter): Graph[(String, Map[String, Map[String, Int]]), KGEdge] =
    {
      val typedVertexRDD: VertexRDD[Map[String, Map[String, Int]]] =
        GraphProfiling.getTypedVertexRDD_Temporal(graph,
          writerSG, type_support, this.TYPE)

      // Now we have the type information collected in the original graph
      val typedAugmentedGraph: Graph[(String, Map[String, Map[String, Int]]), KGEdge] = GraphProfiling.getTypedAugmentedGraph_Temporal(graph,
        writerSG, typedVertexRDD)
      return typedAugmentedGraph
    }

  def getOneEdgePatterns(typedAugmentedGraph: Graph[(String, Map[String, 
    Map[String, Int]]), KGEdge]): VertexRDD[Map[String, Long]] =
    {
      return typedAugmentedGraph.aggregateMessages[Map[String, Long]](
        edge => {
          if (edge.attr.getlabel.equalsIgnoreCase(TYPE) == false) {
            // Extra info for pattern
            if (edge.srcAttr._2.contains("nodeType") &&
              (edge.dstAttr._2.contains("nodeType"))) {
              val dstnodetype =
                edge.dstAttr._2.getOrElse("nodeType", Map("unknownOOT" -> 1))
              val srcnodetype =
                edge.srcAttr._2.getOrElse("nodeType", Map("unknownOOT" -> 1))
              val dstNodeLable: String =
                edge.dstAttr._1
              val srcNodeLable: String = edge.srcAttr._1
              srcnodetype.foreach(s => {
                dstnodetype.foreach(d => {
                  val patternInstance =
                    edge.attr.getlabel.toString() + "\t" + dstNodeLable
                  edge.sendToSrc(Map(s._1 + "\t" + edge.attr.getlabel + "\t" +
                    d._1 + "|"
                    -> 1))
                })
              })
            }
          }
        },
        (pattern1NodeN, pattern2NodeN) => {
          reducePatternsOnNodeV2(pattern1NodeN, pattern2NodeN)
        })
    }
  def reducePatternsOnNodeV2(a: Map[String, Long], b: Map[String, Long]): Map[String, Long] =
    {
      return a |+| b
    }

  def updateGraphWithSinkV2(subgraph_with_pattern: Graph[KGNodeV2Flat, KGEdge]): Graph[KGNodeV2Flat, KGEdge] =
    {
      val all_dest_nodes =
        subgraph_with_pattern.triplets.map(triplets => (triplets.dstId, triplets.dstAttr)).distinct
      val all_source_nodes =
        subgraph_with_pattern.triplets.map(triplets => (triplets.srcId, triplets.srcAttr)).distinct
      val all_sink_nodes =
        all_dest_nodes.subtractByKey(all_source_nodes).map(sink_node => sink_node._2.getlabel).collect

      val graph_with_sink_node_pattern: VertexRDD[Map[String, Long]] =
        subgraph_with_pattern.aggregateMessages[Map[String, Long]](
          edge => {
            if (edge.attr.getlabel.equalsIgnoreCase(TYPE) == false) {
              if (all_sink_nodes.contains(edge.dstAttr.getlabel)) {
                val srcnodepattern = edge.srcAttr.getpattern_map
                val dstNodeLable: String = edge.dstAttr.getlabel
                val srcNodeLable: String = edge.srcAttr.getlabel
                if (srcNodeLable != null && dstNodeLable != null) {
                  srcnodepattern.foreach(s => {
                    if (s._1.contains(edge.attr.getlabel)) // TODO: fix this weak comparison 
                    {
                      edge.sendToDst(Map(s._1 -> 0))
                      val pattern_instances = s._2
                    }
                  })
                }
              }
            }
          },
          (pattern1NodeN, pattern2NodeN) => {
            reducePatternsOnNodeV2(pattern1NodeN, pattern2NodeN)
          })

      val updateGraph_withsink: Graph[KGNodeV2Flat, KGEdge] =
        subgraph_with_pattern.outerJoinVertices(graph_with_sink_node_pattern) {
          case (id, kg_node, Some(nbr)) => new KGNodeV2Flat(kg_node.getlabel, kg_node.getpattern_map |+| nbr, List.empty)
          case (id, kg_node, None) => new KGNodeV2Flat(kg_node.getlabel, kg_node.getpattern_map, List.empty)
        }

      return updateGraph_withsink
    }

  def get_Frequent_SubgraphV2Flat(subgraph_with_pattern: Graph[KGNodeV2Flat, KGEdge],
    result: Graph[PGNode, Int], SUPPORT: Int): Graph[KGNodeV2Flat, KGEdge] =
    {
      /*
	 * This method returns output graph only with frequnet subgraph. 
	 * TODO : need to clean the code
	 */
      println("checking frequent subgraph")
      var commulative_subgraph_index: Map[String, Int] = Map.empty;

      //TODO : Sumit: Use this RDD instead of the Map(below) to filter frequent pattersn
      val pattern_support_rdd: RDD[(String, Long)] =
        subgraph_with_pattern.vertices.flatMap(vertex =>
          {
            vertex._2.getpattern_map.map(f => (f._1, f._2)).toSet
          })
      println("before reduce " + pattern_support_rdd.count)
      //pattern_support_rdd.collect.foreach(f=> println(f.toString))
      val tmpRDD = pattern_support_rdd.reduceByKey((a, b) => a + b)
      println("after reduce " + pattern_support_rdd.count)
      //tmpRDD.collect.foreach(p => writerSGLog.println("gloabl pattern and its supprt" + p._1 + " " + p._2))
      val frequent_pattern_support_rdd = tmpRDD.filter(f => ((f._2 >= SUPPORT) | (f._2 == -1)))
      val frequent_patterns = frequent_pattern_support_rdd.keys.collect
      println()

      var tt1 = System.nanoTime()
      val newGraph: Graph[KGNodeV2Flat, KGEdge] =
        subgraph_with_pattern.mapVertices((id, attr) => {
          var joinedPattern: Map[String, Long] = Map.empty
          val vertex_pattern_map = attr.getpattern_map
          vertex_pattern_map.map(vertext_pattern =>
            {

              if (frequent_patterns.contains(vertext_pattern._1))
                joinedPattern = joinedPattern + ((vertext_pattern._1 -> vertext_pattern._2))
            })
          new KGNodeV2Flat(attr.getlabel, joinedPattern, List.empty)

        })

      val validgraph = newGraph.subgraph(epred =>
        ((epred.srcAttr.getpattern_map != null) || (epred.dstAttr.getpattern_map != null)))
      return validgraph;
    }

  def joinPatterns(writerSG: PrintWriter,
    level: Int): Graph[KGNodeV2Flat, KGEdge] = {

    // Now mine the smaller graph and return the graph with larger patterns
    return getAugmentedGraphNextSizeV2(this.input_graph,
      writerSG: PrintWriter, level)
  }

  /**
   * This method mines the smaller graph and return the graph with larger patterns
   * Every graph pattern grows using 3 types of joins
   * 1. Self Instance Join : two n-size instances of a Pattern (P) on a vertex
   *    are joined to create bigger pattern (PP) of 2*n size
   * 2. Self Pattern Join : two instances of size (n1, n2) of two different patterns
   * 		P1 and P2 on a vertex are joined to create  bigger pattern P1P2 of size n1+n2
   * 3. Edge Pattern Join:  two instances of size (n1, n2) of two different patterns
   * 		P1 and P2 on source and destination vertices are joined to create  bigger
   *    pattern P1P2 of size n1+n2 on the source vertex
   */

  def getAugmentedGraphNextSizeV2(nThPatternGraph: Graph[KGNodeV2Flat, KGEdge],
    writerSG: PrintWriter,
    iteration_id: Int): Graph[KGNodeV2Flat, KGEdge] =
    {

      /*
	   * TODO: isomorphic patterns like these:
	   * 6 (person       friends_with    person          person  likes   product         person  works_at        company         company makes   nt              nt      is_produced_in  state               state   famous_for      national_park   ,3)
		 * 6 (person       likes   product         person  works_at        company         company makes   nt              nt      is_produced_in  state           state   famous_for      national_park               person  friends_with    person  ,3)
		 * 6 (person       friends_with    person          person  works_at        company         company makes   nt              nt      is_produced_in  state           state   famous_for      natio    nal_park   ,3)
	   * 
	   * 
	   */

      // Step 1: First do self join of existing n-size patterns to create 2*n patterns.
      // TODO: This creates many scalability issues without increase in the research quality.
      // so disable it for now
      val g1 = nThPatternGraph //GraphPatternProfiler.self_Instance_Join_GraphV2Flat(nThPatternGraph, iteration_id)

      //Step 2 : Self Join 2 different patterns at a node to create 2*n size pattern
      var res = nThPatternGraph.vertices.map(v => (v._1, v._2.getInstanceCount))
      val newGraph = self_Pattern_Join_GraphV2Flat(g1, iteration_id)
      res = nThPatternGraph.vertices.map(v => (v._1, v._2.getInstanceCount))

      //println("Second maximum number of instances on any node " + res.values.collect.max)

      /*
      *  STEP 3: instead of aggregating entire graph, map each edgetype
      */
      val newGraph1 = filterNonPatternSubGraphV2(newGraph)
      newGraph.unpersist(true) //Blocking call
      val nPlusOneThPatternGraph = edge_Pattern_Join_GraphV2(newGraph1, writerSG, iteration_id)
      newGraph1.unpersist(true) //Blocking call
      return nPlusOneThPatternGraph
    }

  def self_Pattern_Join_GraphV2Flat(updateGraph_withsink: Graph[KGNodeV2Flat, KGEdge],
    join_size: Int): Graph[KGNodeV2Flat, KGEdge] = {
    val newGraph = updateGraph_withsink.mapVertices((id, attr) => {

      /*
       * Initialize some local objects
       */
      var selfJoinPatternstmp: List[(String, Long)] = List.empty
      var joinedPattern: Map[String, Long] = Map.empty

      /*
       * Create a list of the pattern. List provides an ordering of the pattern
       */
      attr.getpattern_map.foreach(sr1 =>
        {
          selfJoinPatternstmp = selfJoinPatternstmp ++ List(sr1);
        })
      val selfJoinPatterns = selfJoinPatternstmp

      /*
       * For each pattern-instance of each pattern at both the source and 
       * destination, iterate over them and join them if they are disjointed 
       * instances.
       * 
       * TODO: because we are not tracking the actual instances, there is not way to 
       * distinguish that follwoing pattern and its support is wrong:
       *  person   buys        product         user5   buys    product  -> 4 
       *  because where the person is same as user5.
       */

      for (i <- 0 until selfJoinPatterns.length) {
        for (j <- i + 1 until selfJoinPatterns.length) {
          //TODO: Another issue becasue of not knowing the exact instances is that 
          // if in the step 1, 2 instances of "user5   buys    product" are used 
          // to form a 2 edge pattern "user5   buys    product user5   buys    product"
          // with support 1. But now in step 2, it will try to join with 
          // sub-pattern "user5   buys    product" and form a 3-edge pattern
          // "user5   buys    product user5   buys    product user5   buys    product"
          // SO i am adding a sub-string check which is not always correct.
          if (!selfJoinPatterns(j)._1.contains(selfJoinPatterns(i)._1) &&
            !selfJoinPatterns(i)._1.contains(selfJoinPatterns(j)._1) &&
            (!FilterHeuristics.checkcross_join(selfJoinPatterns(i)._1, selfJoinPatterns(j)._1)) &&
            (FilterHeuristics.non_overlapping(selfJoinPatterns(i)._1, selfJoinPatterns(j)._1)) &&
            (FilterHeuristics.compatible_join(selfJoinPatterns(i)._1, selfJoinPatterns(j)._1))) {

            val pattern: String = selfJoinPatterns(j)._1.trim() + "|" + "\t" +
              selfJoinPatterns(i)._1.trim() + "|"
            val pg = new PatternGraph()
            ///println("pattern is for dfs "+pattern)
            pg.ConstructPatternGraph(pattern)
            var dfspath = pg.DFS(selfJoinPatterns(j)._1.split("\t")(0))
            if (dfspath.endsWith("|")) {
              val ind = dfspath.lastIndexOf("|")
              dfspath = dfspath.substring(0, ind)

            }
            joinedPattern = joinedPattern + (dfspath -> (selfJoinPatterns(i)._2 * selfJoinPatterns(j)._2))

          }
        }
      }
      new KGNodeV2Flat(attr.getlabel, joinedPattern |+| attr.getpattern_map, List.empty)

    })

    return newGraph
  }

  def filterNonPatternSubGraphV2(updatedGraph: Graph[KGNodeV2Flat, KGEdge]): Graph[KGNodeV2Flat, KGEdge] =
    {
      return updatedGraph.subgraph(epred =>
        ((epred.attr.getlabel.equalsIgnoreCase(TYPE) != true) &&
          ((epred.srcAttr.getpattern_map.size != 0) ||
            (epred.dstAttr.getpattern_map.size != 0))))

    }

  /**
   * edge_Pattern_Join_Graph perform 3rd type of the join out of 3 possible join
   * operations available in the graph mining module.
   *
   * Input to this method is an edge. All the patterns available at the destination
   * of the edge are moved to the source vertex after appending the current edge
   * to destination pattern instance and 1-edge pattern of the current edge to the
   * pattern-key at the destination.
   *
   *
   */
  def edge_Pattern_Join_GraphV2(newGraph1: Graph[KGNodeV2Flat, KGEdge],
    writerSG: PrintWriter, iteration_id: Int): Graph[KGNodeV2Flat, KGEdge] = {
    var t0 = System.currentTimeMillis();
    writerSG.flush()
    val newGraph = newGraph1.subgraph(epred =>
      ((epred.attr.getlabel.equalsIgnoreCase(TYPE) != true) &&
        (epred.srcAttr.getpattern_map.size != 0) &
        (epred.dstAttr.getpattern_map.size != 0)))

    if (iteration_id > 3) {
      val nPlusOneThPatternVertexRDD =
        newGraph.subgraph(epred =>
          ((epred.attr.getlabel.equalsIgnoreCase(TYPE) != true) &&
            ((epred.srcAttr.getpattern_map.size != 0) ||
              (epred.dstAttr.getpattern_map.size != 0)))).aggregateMessages[Long](
          edge => {
            //if (edge.attr.getlabel.equalsIgnoreCase(TYPE) == false)
            println("edge" + edge.srcAttr)
            edge.sendToSrc(1)
          }, (pattern1OnNodeN, pattern2OnNodeN) => {
            println("reduce msg")
            pattern1OnNodeN + pattern2OnNodeN
          })

      //val a = nPlusOneThPatternVertexRDD.mapValues(f=>f).reduce((a,b)=>a+b)
      val b: Double = nPlusOneThPatternVertexRDD.map(f => f._2).sum
      println("total number of mesages are " + b)
      //System.exit(1)
    }

    val nPlusOneThPatternVertexRDD =
      newGraph.aggregateMessages[Map[String, Long]](
        edge => {
          //if (edge.attr.getlabel.equalsIgnoreCase(TYPE) == false)
          sendPatternToNodeV2(edge, iteration_id)

        }, (pattern1OnNodeN, pattern2OnNodeN) => {
          reducePatternsOnNodeV2(pattern1OnNodeN, pattern2OnNodeN)
        })
    val nPlusOneThPatternGraph = joinPatternGraphV2(newGraph, nPlusOneThPatternVertexRDD)
    
    return nPlusOneThPatternGraph
  }

  def sendPatternToNodeV2(edge: EdgeContext[KGNodeV2Flat, KGEdge, Map[String, Long]],
    iteration_id: Int) {

    /*
     * Get source and Destination patterns.
     */
    val allSourceNodePatterns = edge.srcAttr.getpattern_map;
    val allDestinationNodePatterns = edge.dstAttr.getpattern_map
    //    		println("s = " + edge.srcAttr.getlabel + " s size " + allDestinationNodePatterns.size + 
    //    		    " and d= " +   edge.dstAttr.getlabel + " d size " + allDestinationNodePatterns.size )
    if ((allSourceNodePatterns.size > 0) && (allDestinationNodePatterns.size > 0)) {
      allSourceNodePatterns.foreach(sr =>
        {
          if (getPatternSize(sr._1) == 1) {
            //println("pattern size "+getPatternSize(sr._1) + "  in " + iteration_id)
            allDestinationNodePatterns.foreach(dst =>
              {
                {
                  //Ex. <Foo knows Bar> can be joined with any pattern at <Bar>
                  if (sr._1.split("\\t")(2).equals(dst._1.split("\\t")(0))) {

                    sendPatternV2Flat(sr._1, dst._1,
                      sr._2, dst._2, edge)
                  }
                }
              })
          }
        })
    }
    //println("done sending msg")
  }

  def sendPatternV2Flat(pattern1: String, pattern2: String,
    instance1Dst: Long, instance2Dst: Long,
    edge: EdgeContext[KGNodeV2Flat, KGEdge, Map[String, Long]]) {

    var bigger_instance: Int = 0
    edge.sendToSrc(Map(pattern1 + "|" + pattern2 -> instance2Dst))
  }

  //Helper fuction to make sure it is a 1-edge pattern
  def getPatternSize(patternKey: String): Int =
    {
      val tocken_count = patternKey.trim().split("\t").length
      if (tocken_count % 3 == 0)
        return tocken_count / 3
      else
        return -1
    }

 

  def joinPatternGraphV2(nThPatternGraph: Graph[KGNodeV2Flat, KGEdge],
    nPlusOneThPatternVertexRDD: VertexRDD[Map[String, Long]]): Graph[KGNodeV2Flat, KGEdge] =
    {
      val update_tmp_graph =
        nThPatternGraph.outerJoinVertices(nPlusOneThPatternVertexRDD) {
          case (id, a_kgnode, Some(nbr)) =>
            new KGNodeV2Flat(a_kgnode.getlabel,
              a_kgnode.getpattern_map |+| nbr, List.empty)
          case (id, a_kgnode, None) =>
            new KGNodeV2Flat(a_kgnode.getlabel,
              a_kgnode.getpattern_map, List.empty)
        }
      val result = null
      return update_tmp_graph
    }

  /*helper function to clean window graph"
   * 
   */
  def trim(now: Int, windowSize: Int) = {

    //        input_graph = input_graph.subgraph(epred => epred.attr.getdatetime 
    //            > (now - windowSize))
  }

  /**
   * takes gBatch as input which is 'mined' batch graph and merge it with
   * existing window graph.
   */
  def merge(gBatch: CandidateGeneration, sc: SparkContext): CandidateGeneration = {

    if (this.input_graph == null) {
      this.input_graph = gBatch.input_graph
      val result = new CandidateGeneration(minSup)
      result.input_graph = this.input_graph
      return result

    } else {
      val vertices_rdd: RDD[(VertexId, Map[String, Long])] =
        gBatch.input_graph.vertices.map(f => (f._1, f._2.getpattern_map))

      val new_array: Array[VertexId] = 
        gBatch.input_graph.vertices.map(a_vetext => a_vetext._1).toArray

      val new_window_graph = this.input_graph.subgraph(vpred = (vid, attr) => {
        new_array.contains(vid)
      }).joinVertices[Map[String, Long]](vertices_rdd)((id,
        kgnode, new_data) => {
        if (kgnode != null)
          new KGNodeV2Flat(kgnode.getlabel, kgnode.getpattern_map |+| new_data, List.empty)
        else
          new KGNodeV2Flat("", Map.empty, List.empty)
      })

      val result = new CandidateGeneration(minSup)
      result.input_graph = new_window_graph
      return result
    }

  }

  

}
