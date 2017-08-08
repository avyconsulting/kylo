package com.thinkbiganalytics.search;

/*-
 * #%L
 * kylo-search-elasticsearch-rest
 * %%
 * Copyright (C) 2017 ThinkBig Analytics
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.thinkbiganalytics.search.api.Search;
import com.thinkbiganalytics.search.api.SearchIndex;
import com.thinkbiganalytics.search.config.ElasticSearchRestClientConfiguration;
import com.thinkbiganalytics.search.rest.model.HiveColumn;
import com.thinkbiganalytics.search.rest.model.Pair;
import com.thinkbiganalytics.search.rest.model.SearchResult;
import com.thinkbiganalytics.search.rest.model.es.ElasticSearchRestSearchHit;
import com.thinkbiganalytics.search.rest.model.es.ElasticSearchRestSearchResponse;
import com.thinkbiganalytics.search.transform.ElasticSearchRestSearchResultTransform;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Service to search Elasticsearch via rest client
 */
public class ElasticSearchRestService implements Search {

    private static Logger log = LoggerFactory.getLogger(ElasticSearchRestService.class);

    private final static String HTTP_PROTOCOL = "http";
    private final static String POST_METHOD = "POST";
    private final static String PUT_METHOD = "PUT";
    private final static String DELETE_METHOD = "DELETE";
    private final static String SEARCH_ENDPOINT = "_search";

    private ElasticSearchRestClientConfiguration restClientConfig;
    private RestClient restClient;

    public ElasticSearchRestService(ElasticSearchRestClientConfiguration config) {
        this.restClientConfig = config;
        log.info("Search engine: Elasticsearch (rc)");
    }

    @Override
    public void delete(@Nonnull String indexName, @Nonnull String typeName, @Nonnull String id) {
        buildRestClient();
        try {
            restClient.performRequest(
                DELETE_METHOD,
                getIndexDeleteEndPoint(indexName)
            );
        } catch (IOException exception) {
            log.warn("Index deletion failed for index name {}", indexName);
            exception.printStackTrace();
        } finally {
            closeRestClient();
        }
    }

    @Override
    public void commit(@Nonnull String indexName) {
        buildRestClient();
        try {
            restClient.performRequest(
                POST_METHOD,
                getIndexRefreshEndPoint(indexName)
            );
        } catch (IOException exception) {
            log.warn("Index refresh failed for index name {}", indexName);
            exception.printStackTrace();
        } finally {
            closeRestClient();
        }
    }

    @Override
    public void index(@Nonnull String indexName, @Nonnull String typeName, @Nonnull String id, @Nonnull Map<String, Object> fields) {
        buildRestClient();
        try {
            JSONObject jsonContent = new JSONObject(fields);
            HttpEntity httpEntity = new NStringEntity(jsonContent.toString());
            restClient.performRequest(
                PUT_METHOD,
                getIndexWriteEndPoint(indexName, typeName, id),
                Collections.emptyMap(),
                httpEntity);
        } catch (IOException exception) {
            log.warn("Indexing failed for index name {} of type {} with id {}", indexName, typeName, id);
            exception.printStackTrace();
        } finally {
            closeRestClient();
        }
    }

    private String getIndexWriteEndPoint(String indexName, String typeName, String id) {
        return "/" + indexName + "/" + typeName + "/" + id;
    }

    private String getIndexDeleteEndPoint(String indexName) {
        return indexName;
    }

    private String getIndexRefreshEndPoint(String indexName) {
        return "/" + indexName + "/_refresh";
    }

    @Override
    public SearchResult search(String query, int size, int start) {
        buildRestClient();
        String queryForExecution = rewriteQuery(query);
        ElasticSearchRestSearchResponse restSearchResponse = executeRestSearch(queryForExecution, size, start);
        if (restSearchResponse != null) {
            return transformRestResult(query, size, start, restSearchResponse);
        } else {
            log.warn("Search execution produced null response");
            return null;
        }

    }

    private SearchResult transformRestResult(String query, int size, int start, ElasticSearchRestSearchResponse restSearchResponse) {
        ElasticSearchRestSearchResultTransform elasticSearchRestSearchResultTransform = new ElasticSearchRestSearchResultTransform();
        return elasticSearchRestSearchResultTransform.transformRestResult(query, size, start, restSearchResponse);
    }

    private void buildRestClient() {
        if (this.restClient == null) {
            restClient = RestClient.builder(
                new HttpHost(restClientConfig.getHost(),
                             restClientConfig.getPort(),
                             HTTP_PROTOCOL))
                .build();
        }
    }

    private ElasticSearchRestSearchResponse executeRestSearch(String query, int size, int start) {
        try {
            Response response = restClient.performRequest(getHttpMethod(),
                                                          getSearchEndpoint(),
                                                          getParamsMap(),
                                                          getRequestBodyDsl(query, size, start));

            return transformElasticSearchRestResponse(response);

        } catch (IOException ioe) {
            log.error("An error occurred during submitting search request for query: {}, start: {}, size: {}", query, start, size);
            ioe.printStackTrace();
        } finally {
            closeRestClient();
        }

        return null;
    }

    private ElasticSearchRestSearchResponse transformElasticSearchRestResponse(Response response) {
        if (response == null) {
            log.warn("Null response from Elasticsearch (rest client)");
            return null;
        }

        ElasticSearchRestSearchResponse elasticSearchRestSearchResponse = new ElasticSearchRestSearchResponse();

        elasticSearchRestSearchResponse.setRequestLine(response.getRequestLine().toString());
        elasticSearchRestSearchResponse.setHost(response.getHost().toString());
        elasticSearchRestSearchResponse.setStatusLine(response.getStatusLine().toString());
        elasticSearchRestSearchResponse.setRawEntity(response.getEntity());

        for (int i = 0; i < response.getHeaders().length; i++) {
            List<String> currentHeaders = elasticSearchRestSearchResponse.getHeaders();
            currentHeaders.add(response.getHeaders()[i].toString());
            elasticSearchRestSearchResponse.setHeaders(currentHeaders);
        }

        HttpEntity entity = response.getEntity();
        if (entity != null) {
            try {
                String entityString = EntityUtils.toString(response.getEntity());
                JSONObject entityStringJsonObject = new JSONObject(entityString);

                String tookInMs = entityStringJsonObject.getString("took");
                elasticSearchRestSearchResponse.setTookInMillis(Long.parseLong(tookInMs));

                JSONObject hitsJsonObject = entityStringJsonObject.getJSONObject("hits");
                JSONArray hitsJsonArray = hitsJsonObject.getJSONArray("hits");

                List<ElasticSearchRestSearchHit> elasticSearchRestSearchHits = new ArrayList<>();
                for (int i = 0; i < hitsJsonArray.length(); i++) {
                    ElasticSearchRestSearchHit elasticSearchRestSearchHit = new ElasticSearchRestSearchHit();

                    JSONObject currentHitJsonObject = new JSONObject(hitsJsonArray.get(i).toString());
                    elasticSearchRestSearchHit.setIndexName(currentHitJsonObject.get("_index").toString());
                    elasticSearchRestSearchHit.setIndexType(currentHitJsonObject.get("_type").toString());

                    JSONObject currentHitSourceJsonObject = new JSONObject(currentHitJsonObject.get("_source").toString());
                    elasticSearchRestSearchHit.setRawHit(currentHitJsonObject.get("_source").toString());

                    List<Pair> sourceList = new ArrayList<>();
                    Iterator sourceIterator = currentHitSourceJsonObject.keys();
                    while (sourceIterator.hasNext()) {
                        String sourceKey = (String) sourceIterator.next();
                        sourceList.add(new Pair(sourceKey, currentHitSourceJsonObject.get(sourceKey)));

                        if (sourceKey.equals("hiveColumns")) {
                            List<HiveColumn> hiveColumns = new ArrayList<>();

                            String newHiveColumns = "{\"a\":" + currentHitSourceJsonObject.get(sourceKey).toString() + "}";
                            JSONObject hiveColumnsJsonObject = new JSONObject(newHiveColumns);
                            JSONArray hiveColumnsJsonArray = hiveColumnsJsonObject.getJSONArray("a");
                            for (int x = 0; x < hiveColumnsJsonArray.length(); x++) {
                                JSONObject hiveColumnJsonObject = new JSONObject(hiveColumnsJsonArray.get(x).toString());
                                Iterator hiveColumnIterator = hiveColumnJsonObject.keys();
                                String columnName = "";
                                String columnType = "";
                                String columnComment = "";

                                while (hiveColumnIterator.hasNext()) {
                                    String columnKey = (String) hiveColumnIterator.next();
                                    switch (columnKey) {
                                        case "columnName":
                                            columnName = hiveColumnJsonObject.get(columnKey).toString();
                                            break;
                                        case "columnType":
                                            columnType = hiveColumnJsonObject.get(columnKey).toString();
                                            break;
                                        case "columnComment":
                                            columnComment = hiveColumnJsonObject.get(columnKey).toString();
                                            break;
                                        default:
                                            break;
                                    }
                                }
                                hiveColumns.add(new HiveColumn(columnName, columnType, columnComment));
                            }
                            elasticSearchRestSearchHit.setHiveColumns(hiveColumns);
                        }
                        sourceIterator.remove();
                    }
                    elasticSearchRestSearchHit.setSource(sourceList);

                    JSONObject currentHitHighlightJsonObject = new JSONObject(currentHitJsonObject.get("highlight").toString());
                    List<Pair> highlightsList = new ArrayList<>();
                    Iterator highlightIterator = currentHitHighlightJsonObject.keys();
                    while (highlightIterator.hasNext()) {
                        String highlightKey = (String) highlightIterator.next();
                        JSONArray highlightArray = currentHitHighlightJsonObject.getJSONArray(highlightKey);
                        if (highlightArray.length() > 0) {
                            highlightsList.add(new Pair(highlightKey, highlightArray.get(0)));
                        }
                        highlightIterator.remove();
                    }

                    elasticSearchRestSearchHit.setHighlights(highlightsList);
                    elasticSearchRestSearchHits.add(elasticSearchRestSearchHit);
                }
                elasticSearchRestSearchResponse.setElasticSearchRestSearchHits(elasticSearchRestSearchHits);
                return elasticSearchRestSearchResponse;
            } catch (IOException | JSONException exception) {
                System.out.println("An error occurred during decoding search result");
                exception.printStackTrace();
                return null;
            }
        }

        return null;
    }

    private void closeRestClient() {
        try {
            if (restClient != null) {
                restClient.close();
                restClient = null;
            }
        } catch (IOException ioe) {
            log.error("An error occurred during closing rest client");
            ioe.printStackTrace();
        }
    }

    private Map<String, String> getParamsMap() {
        Map<String, String> paramsMap = new HashMap<>();
        paramsMap.put("pretty", "true");
        paramsMap.put("ignore_unavailable", "true");
        return paramsMap;
    }

    private String getSearchEndpoint() {
        return SEARCH_ENDPOINT;
    }

    private String getHttpMethod() {
        return POST_METHOD;
    }

    private HttpEntity getRequestBodyDsl(String query, int size, int start) {
        String jsonBodyString = new JSONObject().toString();
        try {
            JSONObject innerQueryJsonObject = new JSONObject()
                .put("query", query);

            JSONObject queryStringJsonObject = new JSONObject()
                .put("query_string", innerQueryJsonObject);

            JSONArray indicesArray = new JSONArray()
                .put("kylo-categories-metadata")
                .put("kylo-feeds-metadata")
                .put("kylo-categories-default")
                .put("kylo-feeds-default")
                .put("kylo-data")
                .put(SearchIndex.DATASOURCES);

            JSONObject indicesBodyJsonObject = new JSONObject()
                .put("indices", indicesArray)
                .put("query", queryStringJsonObject)
                .put("no_match_query", "none");

            JSONObject indicesJsonObject = new JSONObject()
                .put("indices", indicesBodyJsonObject);

            JSONObject emptyJsonObject = new JSONObject();

            JSONObject fieldsJsonObject = new JSONObject()
                .put("*", emptyJsonObject);

            JSONArray preTagsArray = new JSONArray()
                .put("<font style='font-weight:bold'>");

            JSONArray postTagsArray = new JSONArray()
                .put("</font>");

            JSONObject highlightJsonObject = new JSONObject()
                .put("pre_tags", preTagsArray)
                .put("post_tags", postTagsArray)
                .put("fields", fieldsJsonObject)
                .put("require_field_match", false);

            jsonBodyString = new JSONObject()
                .put("query", indicesJsonObject)
                .put("from", start)
                .put("size", size)
                .put("highlight", highlightJsonObject)
                .toString();

        } catch (JSONException jsonException) {
            log.warn("Could not construct request body query dsl for query: {}, start: {}, size: {}", query, start, size);
            jsonException.printStackTrace();
        }

        return new StringEntity(jsonBodyString, ContentType.create("application/json", "UTF-8"));
    }

    private String rewriteQuery(String query) {
        final String SINGLE_STAR = "*";
        final String DOUBLE_STAR = "**";

        if ((query != null) && (query.trim().equals(SINGLE_STAR))) {
            return DOUBLE_STAR;
        }

        return query;
    }
}
