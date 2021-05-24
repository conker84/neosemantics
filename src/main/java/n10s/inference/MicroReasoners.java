package n10s.inference;

import static org.neo4j.graphdb.RelationshipType.withName;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import n10s.graphconfig.GraphConfig;
import n10s.result.NodeResult;
import n10s.result.RelAndNodeResult;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;
import org.neo4j.logging.Log;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;
import org.neo4j.procedure.UserFunction;

public class MicroReasoners {

  private static final String sloInferenceFormatReturnClassNames = "CALL db.labels() YIELD label "
      + " WITH collect(label) as labels MATCH path = (c:`%1$s`)<-[:`%3$s`*]-(s:`%1$s`) "
      + " WHERE s.`%2$s` in labels AND NOT (c)-[:`%3$s`]->() AND any(x in nodes (path) "
      + " WHERE x.`%2$s` = $virtLabel ) RETURN COLLECT(DISTINCT s.`%2$s`) + $virtLabel  as l";

  private static final String subcatPathQuery =
      "MATCH (x:`%1$s` { `%2$s`: $oneOfCats } ) MATCH (y:`%1$s` { `%2$s`: $virtLabel } ) "
          + " WHERE  (x)-[:`%3$s`*]->(y) RETURN count(x) > 0 as isTrue ";
  private static final String scoInferenceCypherTopDownQuery = "MATCH (cat)<-[:`%1$s`*0..]-(subcat) WHERE id(cat) = $catId RETURN collect(DISTINCT id(subcat)) AS catIds";
  private static final String scoInferenceCypherBottomUpQuery = "MATCH (cat)<-[:`%1$s`*0..]-(subcat) WHERE id(subcat) = $catId RETURN collect(DISTINCT id(cat)) AS catIds";
  private static final String sroInferenceFormatReturnRelNamesQuery = "RETURN $virtRel as r UNION MATCH (:`%1$s` { `%2$s`: $virtRel})<-[:`%3$s`*]-(sr:`%1$s`) RETURN DISTINCT sr.`%2$s` as r";
  //TODO: come up with a well defined approach for class and rel name properties
  private static final String DEFAULT_CAT_NAME_PROP_NAME = "name";
  private static final String DEFAULT_REL_NAME_PROP_NAME = "name";
  private static final boolean DEFAULT_SEARCH_TOP_DOWN = false;

  @Context
  public GraphDatabaseService db;

  @Context
  public Transaction tx;

  @Context
  public Log log;

  /* get nodes with a given label explicit or inferred.
   * semantics (cat:Cat { name: 'xyz'})-[:SCO]->(parent:Cat { name: ''}) */

  @Procedure(mode = Mode.READ)
  @Description("n10s.inference.nodesLabelled('label') - returns all nodes with label 'label' or its sublabels.")
  public Stream<NodeResult> nodesLabelled(@Name("label") String virtLabel,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) throws MicroReasonerException {
    final GraphConfig gc = getGraphConfig();

    if(gc == null && missingParams(props, "catLabel", "catNameProp", "subCatRel")){
      throw new MicroReasonerException("No GraphConfig or in-procedure params. Method cannot be run.");
    }

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("virtLabel", virtLabel);
    Result results = tx.execute(String.format(sloInferenceFormatReturnClassNames,
        (props.containsKey("catLabel") ? (String) props.get("catLabel") : gc.getClassLabelName()),
        (props.containsKey("catNameProp") ? (String) props.get("catNameProp")
            : DEFAULT_CAT_NAME_PROP_NAME),
        (props.containsKey("subCatRel") ? (String) props.get("subCatRel") : gc.getSubClassOfRelName())),
        params);

    List<String> labelList = (List<String>) results.next().get("l");

    StringBuilder sb = new StringBuilder();
    sb.append("cypher runtime=slotted ");
    sb.append("unwind [] as result return result ");
    labelList
        .forEach(x -> sb.append(" UNION MATCH (x:`").append(x).append("`) RETURN x as result "));

    return tx.execute(sb.toString()).stream().map(n -> (Node) n.get("result"))
        .map(NodeResult::new);

  }

  private GraphConfig getGraphConfig() {
    try {
      return new GraphConfig(tx);
    } catch (GraphConfig.GraphConfigNotFound graphConfigNotFound) {
      //no graph config
      return null;
    }
  }

  /* in this case the node representing the category exist in the graph and is explicitly linked to the instances of the category
   *  hence the use of a node as param */
  @Procedure(mode = Mode.READ)
  @Description("n10s.inference.nodesInCategory('category') - returns all nodes connected to Node 'catNode' or its subcategories.")
  public Stream<NodeResult> nodesInCategory(@Name("category") Node catNode,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) throws MicroReasonerException {

    final GraphConfig gc = getGraphConfig();

    //if no graphconfig (or ontoconfig) and no required in-function params, funcion cannot be invoked
    if(gc == null && missingParams(props, "subCatRel")){
      throw new MicroReasonerException("No GraphConfig or in-procedure params. Method cannot be run.");
    }

    final String inCatRelName = (props.containsKey("inCatRel") ? (String) props.get("inCatRel")
        : getDefaultIncatRel(gc));
    final String subCatRelName = (props.containsKey("subCatRel") ? (String) props.get("subCatRel")
        : gc.getSubClassOfRelName());

    Map<String, Object> params = new HashMap<>();
    params.put("catId", catNode.getId());

    String cypher = "MATCH (rootCategory)<-[:`" + subCatRelName + "`*0..]-()<-[:`" +
        inCatRelName + "`]-(individual) WHERE id(rootCategory) = $catId RETURN individual ";

    return tx.execute(cypher, params).stream().map(n -> (Node) n.get("individual"))
        .map(NodeResult::new);
  }

  private String getDefaultIncatRel(GraphConfig gc) {

    if(gc.getHandleRDFTypes() == GraphConfig.GRAPHCONF_RDFTYPES_AS_NODES ||
            gc.getHandleRDFTypes() == GraphConfig.GRAPHCONF_RDFTYPES_AS_LABELS_AND_NODES){
      if(gc.getHandleVocabUris() == GraphConfig.GRAPHCONF_VOC_URI_IGNORE ||
              gc.getHandleVocabUris() == GraphConfig.GRAPHCONF_VOC_URI_MAP){
        return "type";
      } else if (gc.getHandleVocabUris() == GraphConfig.GRAPHCONF_VOC_URI_SHORTEN ||
              gc.getHandleVocabUris() == GraphConfig.GRAPHCONF_VOC_URI_SHORTEN_STRICT){
        return "rdf__type";
      } else if (gc.getHandleVocabUris() == GraphConfig.GRAPHCONF_VOC_URI_KEEP){
        return "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
      }
    }
    return "IN_CATEGORY";

  }

  private List<Long> getSubcatIds(Node catNode, String subCatRelName, GraphConfig gc) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("catId", catNode.getId());
    return (List<Long>) tx.execute( String.format(scoInferenceCypherTopDownQuery,
            (subCatRelName == null ? gc.getSubClassOfRelName():subCatRelName)), params).next().get("catIds");
            //scoInferenceCypherTopDown
        //: scoInferenceCypherTopDown.replace("SCO", subCatRelName)), params).next().get("catIds");
  }

  private List<Long> getSuperCatIds(long catNodeId, String subCatRelName, GraphConfig gc) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("catId", catNodeId);
    return (List<Long>) tx.execute( String.format(scoInferenceCypherBottomUpQuery,
            (subCatRelName == null ? gc.getSubClassOfRelName():subCatRelName)), params).next().get("catIds");
  }

  @Procedure(mode = Mode.READ)
  @Description(
      "n10s.inference.getRels(node,'rel', { relDir: '>'} ) - returns all relationships "
          + "of type 'rel' or its subtypes along with the target nodes.")
  public Stream<RelAndNodeResult> getRels(@Name("node") Node node, @Name("rel") String virtRel,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) throws MicroReasonerException {

    final GraphConfig gc = getGraphConfig();

    //if no graphconfig (or ontoconfig) and no required in-function params, funcion cannot be invoked
    if(gc == null && missingParams(props, "relLabel","subRelRel")){
      throw new MicroReasonerException("No GraphConfig or in-procedure params. Method cannot be run.");
    }

    String directionString = (props.containsKey("relDir") ? (String) props.get("relDir") : "");
    Direction direction = (directionString.equals(">") ? Direction.OUTGOING
        : (directionString.equals("<") ? Direction.INCOMING : Direction.BOTH));

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("virtRel", virtRel);

    Result results = tx.execute(String.format(sroInferenceFormatReturnRelNamesQuery,
        (props.containsKey("relLabel") ? (String) props.get("relLabel") : gc.getObjectPropertyLabelName()),
        (props.containsKey("relNameProp") ? (String) props.get("relNameProp")
            : DEFAULT_REL_NAME_PROP_NAME),
        (props.containsKey("subRelRel") ? (String) props.get("subRelRel") : gc.getSubPropertyOfRelName())),
        params);
    Set<RelationshipType> rts = new HashSet<RelationshipType>();
    while (results.hasNext()) {
      rts.add(withName((String) results.next().get("r")));
    }

    return StreamSupport.stream(
        node.getRelationships(direction, rts.toArray(new RelationshipType[0])).spliterator(), true)
        .map(n -> new RelAndNodeResult(n, n.getOtherNode(node)));

  }


  @UserFunction
  @Description(
      "n10s.inference.hasLabel(node,'label',{}) - checks whether node is explicitly or "
          + "implicitly labeled as 'label'.")
  public boolean hasLabel(
      @Name("node") Node individual,
      @Name("label") String label,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) throws MicroReasonerException {

    final GraphConfig gc = getGraphConfig();

    //if no graphconfig (or ontoconfig) and no required in-function params, funcion cannot be invoked
    if(gc == null && missingParams(props, "catLabel", "subCatRel")){
      throw new MicroReasonerException("No GraphConfig or in-function params. Method cannot be run.");
    }

    String queryString = String.format(subcatPathQuery,
        (props.containsKey("catLabel") ? (String) props.get("catLabel") : gc.getClassLabelName()),
        (props.containsKey("catNameProp") ? (String) props.get("catNameProp")
            : DEFAULT_CAT_NAME_PROP_NAME),
        (props.containsKey("subCatRel") ? (String) props.get("subCatRel") : gc.getSubClassOfRelName()));

    Map<String, Object> params = new HashMap<String, Object>();
    params.put("virtLabel", label);

    Iterable<Label> labels = individual.getLabels();
    boolean is = false;
    for (Label l : labels) {
      params.put("oneOfCats", l.name());
      is |= (l.name().equals(label) ? true
          : tx.execute(queryString, params).next().get("isTrue").equals(true));
    }

    return is;
  }

  private boolean missingParams(Map<String, Object> props, String... paramNames) {
    boolean missing = false;
    for (String param:paramNames) {
      missing |= !props.containsKey(param);
    }
    return  missing;
  }


  @UserFunction
  @Description("n10s.inference.inCategory(node, category, {}) - checks whether node is explicitly or implicitly in a category.")
  public boolean inCategory(
      @Name("node") Node individual, @Name("category") Node category,
      @Name(value = "params", defaultValue = "{}") Map<String, Object> props) {

    final GraphConfig gc = getGraphConfig();

    final String inCatRelName = (props.containsKey("inCatRel") ? (String) props.get("inCatRel")
        : getDefaultIncatRel(gc));
    final String subCatRelName = (props.containsKey("subCatRel") ? (String) props.get("subCatRel")
        : gc.getSubClassOfRelName());
    final boolean searchTopDown = (props.containsKey("searchTopDown") ? (boolean) props
        .get("searchTopDown")
        : DEFAULT_SEARCH_TOP_DOWN);

    Iterator<Relationship> relIterator = individual
        .getRelationships(Direction.OUTGOING, RelationshipType.withName(inCatRelName)).iterator();

    if (searchTopDown) {
      List<Long> catIds = getSubcatIds(category, subCatRelName, gc);
      boolean is = false;
      while (!is && relIterator.hasNext()) {
        is |= catIds.contains(relIterator.next().getEndNode().getId());
      }
      return is;

    } else {
      boolean is = false;
      while (!is && relIterator.hasNext()) {
        List<Long> catIds = getSuperCatIds(relIterator.next().getEndNode().getId(), subCatRelName, gc);
        is |= catIds.contains(category.getId());
      }
      return is;

    }


  }

}
