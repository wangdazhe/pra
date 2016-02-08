package edu.cmu.ml.rtw.pra.features.extractors

import edu.cmu.ml.rtw.pra.data.Instance
import edu.cmu.ml.rtw.pra.data.NodeInstance
import edu.cmu.ml.rtw.pra.data.NodePairInstance
import edu.cmu.ml.rtw.pra.features.BaseEdgeSequencePathType
import edu.cmu.ml.rtw.pra.features.BasicPathTypeFactory
import edu.cmu.ml.rtw.pra.graphs.Graph

// A FeatureMatcher is constructed (generally) from a single feature, and can filter a graph to
// find subgraphs that would have generated the feature.  This is very similar to the old
// PathFollower logic, except we're not worrying about computing probabilities here, just finding
// whether a particular node or node pair matches a feature.
//
// The use case is, say, given an entity and a set of features (assuming NodePairInstances), find a
// set of entities that will have non-zero probability in a model using these features.  For
// NodeInstances instead of NodePairInstances, you don't generally have as good a starting place,
// so you might have to iterate over all instances in the graph, which is why this is only
// currently implemented for NodePairInstances.
//
// TODO(matt): Do I need the type parameter here?  I don't currently use it in any of the methods
// in this class.  I guess it's marginally useful to ensure type consistency with the dataset it's
// used with, but that's about it.
trait FeatureMatcher[T <: Instance] {
  // If we've gone stepsTaken steps, have we matched the whole feature?  stepsTaken may not be
  // sufficient for some features to tell if they're finished, but it is enough for all of the
  // features I plan on implementing right now.  The rest will have to wait, and get a more
  // complicated API.
  def isFinished(stepsTaken: Int): Boolean

  // Is this edge type acceptable after stepsTaken steps, according to this feature?  If this is a
  // plain PRA-style feature, this will just say if the edgeId matches step stepsTaken in the path
  // type.  More complicated features may have more complicated logic here (or just always return
  // true).
  def edgeOk(edgeId: Int, reverse: Boolean, stepsTaken: Int): Boolean

  // Is this node acceptable after stepsTaken steps?  For PRA-style features, this will always
  // return true, but some feature types need to check this.
  def nodeOk(nodeId: Int, stepsTaken: Int): Boolean

  // Querying edgeOk for all edge types at a node is potentially very inefficient, if we know that
  // there is just one edge type that is allowed.  If it's possible to compute such a thing, this
  // method returns the set of allowed edge types at a given step in a search.  If no such set is
  // computable (e.g., any edge type is allowed), None is returned, and the matching algorithm will
  // just query edgeOk on all present edge types.  The tuple in the returned set is (edgeId,
  // reverse?).
  def allowedEdges(stepsTaken: Int): Option[Set[(Int, Boolean)]]

  // Similar to allowedEdges, but for nodes.
  def allowedNodes(stepsTaken: Int): Option[Set[Int]]
}

class EmptyFeatureMatcher[T <: Instance] extends FeatureMatcher[T] {
  override def isFinished(stepsTaken: Int) = true
  override def edgeOk(edgeId: Int, reverse: Boolean, stepsTaken: Int) = false
  override def nodeOk(nodeId: Int, stepsTaken: Int) = false
  override def allowedEdges(stepsTaken: Int) = None
  override def allowedNodes(stepsTaken: Int) = None
}

case class PraFeatureMatcher(
  pathType: BaseEdgeSequencePathType
) extends FeatureMatcher[NodePairInstance] {
  override def isFinished(stepsTaken: Int): Boolean = stepsTaken >= pathType.numHops
  override def edgeOk(edgeId: Int, reverse: Boolean, stepsTaken: Int): Boolean = {
    stepsTaken < pathType.numHops &&
    pathType.edgeTypes(stepsTaken) == edgeId &&
    pathType.reverse(stepsTaken) == reverse
  }
  override def nodeOk(nodeId: Int, stepsTaken: Int): Boolean = true
  override def allowedEdges(stepsTaken: Int) = {
    if (stepsTaken < pathType.numHops) {
      Some(Set((pathType.edgeTypes(stepsTaken), pathType.reverse(stepsTaken))))
    } else {
      None
    }
  }
  override def allowedNodes(stepsTaken: Int) = None
}

object PraFeatureMatcher {
  // If the given feature looks like a feature from PraFeatureExtractor, this method will create a
  // PraFeatureMatcher from the given arguments.  If the feature does not look like a
  // PraFeatureExtractor feature, it will return None.
  def create(feature: String, startFromSourceNode: Boolean, graph: Graph): Option[PraFeatureMatcher] = {
    if (!feature.startsWith("-") || !feature.endsWith("-")) return None
    // This one is because of the odd behavior of Java's String.split() - it ignores all delimiters
    // at the end of the string, so we won't actually get an error from feature parsing code in a
    // case like this.
    if (feature.endsWith("--")) return None
    if (feature.length <= 2) return None
    try {
      val factory = new BasicPathTypeFactory(graph)
      val pathType = factory.fromHumanReadableString(feature)
      if (startFromSourceNode) {
        Some(PraFeatureMatcher(pathType.asInstanceOf[BaseEdgeSequencePathType]))
      } else {
        val reversedPathType = factory.concatenatePathTypes(factory.emptyPathType, pathType)
        Some(PraFeatureMatcher(reversedPathType.asInstanceOf[BaseEdgeSequencePathType]))
      }
    } catch {
      // This happens when there are edge types in the feature string that aren't in the graph.
      case e: NoSuchElementException => None
      // This happens when there's a substring like "--" in the feature.
      case e: StringIndexOutOfBoundsException => None
    }
  }
}
