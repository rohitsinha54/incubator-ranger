/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.tagsync.source.atlasrest;

import com.google.gson.Gson;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.apache.atlas.typesystem.IReferenceableInstance;
import org.apache.atlas.typesystem.IStruct;
import org.apache.atlas.typesystem.Struct;
import org.apache.atlas.typesystem.json.InstanceSerialization;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.ranger.admin.client.datatype.RESTResponse;
import org.apache.ranger.plugin.util.RangerRESTClient;
import org.apache.ranger.tagsync.source.atlas.AtlasEntityWithTraits;
import org.apache.ranger.tagsync.source.atlas.AtlasResourceMapperUtil;

import java.util.*;

@SuppressWarnings("unchecked")
public class AtlasRESTUtil {
	private static final Logger LOG = Logger.getLogger(AtlasRESTUtil.class);

	private static final String REST_MIME_TYPE_JSON = "application/json" ;
	private static final String API_ATLAS_TYPES    = "api/atlas/types";
	private static final String API_ATLAS_ENTITIES = "api/atlas/entities?type=";
	private static final String API_ATLAS_ENTITY   = "api/atlas/entities/";
	private static final String API_ATLAS_TYPE     = "api/atlas/types/";

	private static final String RESULTS_ATTRIBUTE               = "results";
	private static final String DEFINITION_ATTRIBUTE            = "definition";
	private static final String VALUES_ATTRIBUTE                = "values";
	private static final String TRAITS_ATTRIBUTE                = "traits";
	private static final String TYPE_NAME_ATTRIBUTE             = "typeName";
	private static final String TRAIT_TYPES_ATTRIBUTE           = "traitTypes";
	private static final String SUPER_TYPES_ATTRIBUTE           = "superTypes";
	private static final String ATTRIBUTE_DEFINITIONS_ATTRIBUTE = "attributeDefinitions";
	private static final String NAME_ATTRIBUTE                  = "name";

	private final Gson gson = new Gson();

	private RangerRESTClient atlasRESTClient;

	public AtlasRESTUtil(String atlasEndpoint) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> AtlasRESTUtil(" + atlasEndpoint + ")");
		}

		if (!atlasEndpoint.endsWith("/")) {
			atlasEndpoint += "/";
		}

		// This uses RangerRESTClient to invoke REST APIs on Atlas. It will work only if scheme of URL is http
		atlasRESTClient = new RangerRESTClient();
		atlasRESTClient.setUrl(atlasEndpoint);

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== AtlasRESTUtil(" + atlasEndpoint + ")");
		}
	}

	public List<AtlasEntityWithTraits> getEntitiesWithTraits() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> getEntriesWithTraits()");
		}

		List<AtlasEntityWithTraits> ret = new ArrayList<AtlasEntityWithTraits>();

		Map<String, Object> typesResponse = atlasAPI(API_ATLAS_TYPES);

		List<String> types = getAttribute(typesResponse, RESULTS_ATTRIBUTE, List.class);

		if (CollectionUtils.isNotEmpty(types)) {

			for (String type : types) {

				if (!AtlasResourceMapperUtil.isEntityTypeHandled(type)) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("Not fetching Atlas entities of type:" + type);
					}
					continue;
				}

				Map<String, Object> entitiesResponse = atlasAPI(API_ATLAS_ENTITIES + type);

				List<String> guids = getAttribute(entitiesResponse, RESULTS_ATTRIBUTE, List.class);

				if (CollectionUtils.isEmpty(guids)) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("No Atlas entities for type:" + type);
					}
					continue;
				}

				for (String guid : guids) {

					Map<String, Object> entityResponse = atlasAPI(API_ATLAS_ENTITY + guid);

					Map<String, Object> definition = getAttribute(entityResponse, DEFINITION_ATTRIBUTE, Map.class);

					Map<String, Object> traitsAttribute = getAttribute(definition, TRAITS_ATTRIBUTE, Map.class);

					if (MapUtils.isNotEmpty(traitsAttribute)) {

						List<IStruct> allTraits = new LinkedList<>();

						for (Map.Entry<String, Object> entry : traitsAttribute.entrySet()) {

							Map<String, Object> trait = (Map<String, Object>) entry.getValue();

							Map<String, Object> traitValues = getAttribute(trait, VALUES_ATTRIBUTE, Map.class);
							String traitTypeName = getAttribute(trait, TYPE_NAME_ATTRIBUTE, String.class);

							if (StringUtils.isEmpty(traitTypeName)) {
								continue;
							}

							List<IStruct> superTypes = getTraitSuperTypes(getTraitType(traitTypeName), traitValues);

							Struct trait1 = new Struct(traitTypeName, traitValues);

							allTraits.add(trait1);
							allTraits.addAll(superTypes);
						}

						IReferenceableInstance entity = InstanceSerialization.fromJsonReferenceable(gson.toJson(definition), true);

						if (entity != null) {
							AtlasEntityWithTraits entityWithTraits = new AtlasEntityWithTraits(entity, allTraits);
							ret.add(entityWithTraits);
						} else {
							if (LOG.isInfoEnabled()) {
								LOG.info("Could not create Atlas entity from its definition, type=" + type + ", guid=" + guid);
							}
						}

					}

				}
			}
			if (LOG.isDebugEnabled()) {
				LOG.debug("<== getEntriesWithTraits()");
			}
		}

		return ret;
	}

	private Map<String, Object> getTraitType(String traitName) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> getTraitType(" + traitName + ")");
		}
		Map<String, Object> ret = null;

		Map<String, Object> typeResponse = atlasAPI(API_ATLAS_TYPE + traitName);

		Map<String, Object> definition = getAttribute(typeResponse, DEFINITION_ATTRIBUTE, Map.class);

		List traitTypes = getAttribute(definition, TRAIT_TYPES_ATTRIBUTE, List.class);

		if (CollectionUtils.isNotEmpty(traitTypes)) {
			ret = (Map<String, Object>) traitTypes.get(0);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== getTraitType(" + traitName + ")");
		}
		return ret;
	}

	private List<IStruct> getTraitSuperTypes(Map<String, Object> traitType, Map<String, Object> values) {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> getTraitSuperTypes()");
		}
		List<IStruct> ret = new LinkedList<>();

		if (traitType != null) {

			List<String> superTypeNames = getAttribute(traitType, SUPER_TYPES_ATTRIBUTE, List.class);

			if (CollectionUtils.isNotEmpty(superTypeNames)) {
				for (String superTypeName : superTypeNames) {

					Map<String, Object> superTraitType = getTraitType(superTypeName);

					if (superTraitType != null) {
						List<Map<String, Object>> attributeDefinitions = (List) superTraitType.get(ATTRIBUTE_DEFINITIONS_ATTRIBUTE);

						Map<String, Object> superTypeValues = new HashMap<>();
						for (Map<String, Object> attributeDefinition : attributeDefinitions) {

							String attributeName = attributeDefinition.get(NAME_ATTRIBUTE).toString();
							if (values.containsKey(attributeName)) {
								superTypeValues.put(attributeName, values.get(attributeName));
							}
						}

						List<IStruct> superTraits = getTraitSuperTypes(getTraitType(superTypeName), values);

						Struct superTrait = new Struct(superTypeName, superTypeValues);

						ret.add(superTrait);
						ret.addAll(superTraits);
					}
				}
			}
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== getTraitSuperTypes()");
		}
		return ret;
	}

	private Map<String, Object> atlasAPI(String endpoint) {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> atlasAPI(" + endpoint + ")");
		}
		Map<String, Object> ret = new HashMap<String, Object>();

		try {
			WebResource webResource = atlasRESTClient.getResource(endpoint);
			ClientResponse response = webResource.accept(REST_MIME_TYPE_JSON).type(REST_MIME_TYPE_JSON).get(ClientResponse.class);

			if (response != null && response.getStatus() == 200) {
				ret = response.getEntity(ret.getClass());
			} else {
				RESTResponse resp = RESTResponse.fromClientResponse(response);
				LOG.error("Error getting atlas data request=" + webResource.toString()
						+ ", response=" + resp.toString());
			}
		} catch (Exception exception) {
			LOG.error("Exception when fetching Atlas objects.", exception);
			ret = null;
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== atlasAPI(" + endpoint + ")");
		}
		return ret;
	}

	private <T> T getAttribute(Map<String, Object> map, String name, Class<T> type) {
		return MapUtils.isNotEmpty(map) ? type.cast(map.get(name)) : null;
	}

}
