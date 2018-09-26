package solrml.handler;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.classification.ClassificationResult;
import org.apache.lucene.classification.Classifier;
import org.apache.lucene.classification.KNearestNeighborClassifier;
import org.apache.lucene.classification.SimpleNaiveBayesClassifier;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.QParser;
import org.apache.solr.search.QParserPlugin;
import org.apache.solr.search.QueryParsing;

public class ClassifierHandler extends RequestHandlerBase {
	
	public static final String ANALYZE = "analyze";
	
	public static final String DOMAIN = "domain";
	
	public static final String RANGE = "range";
	
	protected String algorithm = null;
	
	protected int k = 0;
	
	protected int minDf = 0;
	
	protected int minTf = 0;

	@Override
	public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
		SolrParams params = req.getParams();
		
		String q = params.get(CommonParams.Q);
		String analyzeQ = params.get(ANALYZE);
		String defType = params.get(QueryParsing.DEFTYPE, QParserPlugin.DEFAULT_QTYPE);
		Query query = null;
		if (q != null) {
            QParser parser = QParser.getParser(q, defType, req);
            query = parser.getQuery();
        }
		
		if (analyzeQ == null) {
			analyzeQ = q;
		}
		
        String[] fqs = req.getParams().getParams(CommonParams.FQ);
        if (fqs != null && fqs.length != 0) {
          BooleanQuery.Builder filteredQuery = new BooleanQuery.Builder();
          filteredQuery.add(query, Occur.SHOULD);
          for (String fq : fqs) {
            if (fq != null && fq.trim().length() != 0) {
              QParser fqp = QParser.getParser(fq, req);
              filteredQuery.add(fqp.getQuery(), Occur.FILTER);
            }
          }
          query = filteredQuery.build();
        }
		
		String[] trainFields = params.getParams(DOMAIN);
		String targetField = params.get(RANGE);
		IndexReader indexReader = req.getSearcher().getIndexReader();
		Analyzer fieldAnalyzer = req.getSearcher().getSchema().getField(trainFields[0]).getType().getQueryAnalyzer();
		Classifier<BytesRef> classifier;
		
		switch (algorithm) {
			case "knn":
				classifier = new KNearestNeighborClassifier(indexReader, null, fieldAnalyzer, query, k, minDf, minTf, targetField, trainFields);
				break;
			case "bayes":
				classifier = new SimpleNaiveBayesClassifier(indexReader, fieldAnalyzer, query, targetField, trainFields);
				break;
			default:
				throw new Exception();
		}
		
		java.util.List<ClassificationResult<BytesRef>> result = classifier.getClasses(analyzeQ);
		
		ArrayList <HashMap<String, String>> classes = new ArrayList<HashMap<String,String>>();
		double bestScore = -1;
		for (ClassificationResult<BytesRef> res : result) {
		  HashMap<String, String> mp = new HashMap<String,String>();
		  
		  double score = res.getScore();
		  if (bestScore < 0) {
			  bestScore = score;
		  }
		  
		  if (score != bestScore) {
			  break;
		  }

		  mp.put("class", res.getAssignedClass().utf8ToString());
		  mp.put("score", (new Double(score)).toString());
		  classes.add(mp);
		}
		
		rsp.add("algorithm", algorithm);
		rsp.add("classes", classes);
	}

	@Override
	public String getDescription() {
		return "A response handler for classifying text based on a collection of documents."; 
	}

	@Override
	public void init(NamedList args) {
		super.init(args);
		
		try {
			SolrParams params = args.toSolrParams();
			algorithm = params.get("algorithm");
			k = params.getInt("knn.k");
			minDf = params.getInt("knn.minDf");
			minTf = params.getInt("knn.minTf");
		}
		catch (Exception e) {
		}
	}
	
}