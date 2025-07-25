/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.reporting.serializer;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.converters.ConverterLookup;
import com.thoughtworks.xstream.converters.DataHolder;
import com.thoughtworks.xstream.core.MapBackedDataHolder;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.xml.DomDriver;
import com.thoughtworks.xstream.mapper.Mapper;
import com.thoughtworks.xstream.mapper.MapperWrapper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.cohort.definition.CohortDefinition;
import org.openmrs.module.reporting.dataset.definition.CohortIndicatorAndDimensionDataSetDefinition;
import org.openmrs.module.reporting.evaluation.parameter.Mapped;
import org.openmrs.module.reporting.evaluation.parameter.Parameter;
import org.openmrs.module.reporting.report.definition.ReportDefinition;
import org.openmrs.module.serialization.xstream.XStreamShortSerializer;
import org.openmrs.module.serialization.xstream.mapper.CGLibMapper;
import org.openmrs.module.serialization.xstream.mapper.HibernateCollectionMapper;
import org.openmrs.module.serialization.xstream.mapper.JavassistMapper;
import org.openmrs.module.serialization.xstream.mapper.NullValueMapper;
import org.openmrs.serialization.SerializationException;
import org.openmrs.serialization.SimpleXStreamSerializer;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;

public class ReportingSerializer extends XStreamShortSerializer {

	private static ThreadLocal<DataHolder> cache = new ThreadLocal<DataHolder>();

	private final Log log = LogFactory.getLog(this.getClass());

	private boolean xstreamSecuritySetup = false;
	
	/**
	 * @throws SerializationException
	 * @should serialize a cohort definition
	 * @should serialize an indicator that contains a persisted cohort definition
	 * @should serialize an indicator that contains an unsaved cohort definition
	 */
	public ReportingSerializer() throws SerializationException {
	    super(new XStream(new DomDriver()) {
	    	
	    	/**
	    	 * This method copied from XStreamSerializer constructor.
	    	 */
			protected MapperWrapper wrapMapper(MapperWrapper next) {
				MapperWrapper mapper = new CGLibMapper(next);
				mapper = new JavassistMapper(mapper);
				mapper = new HibernateCollectionMapper(mapper);
				mapper = new NullValueMapper(mapper);
				return mapper;
			}
			
	    	/**
	    	 * Override a mid-level XStream method to reuse a DataHolder cache if one is available 
	    	 */
	        public Object unmarshal(HierarchicalStreamReader reader, Object root) {
	            return unmarshal(reader, root, cache.get());
	        }
	    });

	    Mapper mapper = xstream.getMapper();
	    ConverterLookup converterLookup = xstream.getConverterLookup();

	    xstream.registerConverter(new PersonQueryConverter(mapper, converterLookup));
	    xstream.registerConverter(new CohortDefinitionConverter(mapper, converterLookup));
	    xstream.registerConverter(new EncounterQueryConverter(mapper, converterLookup));
	    xstream.registerConverter(new ObsQueryConverter(mapper, converterLookup));
		xstream.registerConverter(new CalculationRegistrationShortConverter(mapper, converterLookup));

		xstream.registerConverter(new PersonDataDefinitionConverter(mapper, converterLookup));
	    xstream.registerConverter(new PatientDataDefinitionConverter(mapper, converterLookup));
	    xstream.registerConverter(new EncounterDataDefinitionConverter(mapper, converterLookup));
	    
	    xstream.registerConverter(new DataSetDefinitionConverter(mapper, converterLookup));
	    
	    xstream.registerConverter(new DimensionConverter(mapper, converterLookup));
	    xstream.registerConverter(new IndicatorConverter(mapper, converterLookup));

		xstream.registerConverter(new ReportDefinitionConverter(mapper, converterLookup));
		xstream.allowTypes(new Class[] {Parameter.class, Mapped.class, CohortIndicatorAndDimensionDataSetDefinition.CohortIndicatorAndDimensionSpecification.class});
	}
	
	@Override
	synchronized public <T> T deserialize(String serializedObject, Class<? extends T> clazz) throws SerializationException {
		if (!xstreamSecuritySetup) {
			setupXStreamSecurity();
			xstreamSecuritySetup = true;
		}
		boolean cacheOwner = cache.get() == null;
		if (cacheOwner) {
			cache.set(new MapBackedDataHolder());
		}
		try {
			return super.deserialize(serializedObject, clazz);
		} finally {
			if (cacheOwner)
				cache.remove();
		}
	}

    /**
     * The OpenMRS serializer interface supports serializing an Object to a String, but when we are serializing a
     * complete ReportData, it may be too memory-intensive to create this String. This method lets you serialize to XML
     * in a streaming fashion.
     * @param object
     * @param out
     */
    public void serializeToStream(Object object, OutputStream out) {
        try {
            xstream.toXML(object, new OutputStreamWriter(out, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Unsupported encoding", e);
        }
    }

	/**
	 * Sets up xstream security on the Reporting Serializer to match the OpenMRS core security configuration
	 */
	public void setupXStreamSecurity() throws SerializationException {
		log.debug("Setting up xstream security on ReportingSerializer");
		SimpleXStreamSerializer serializer = null;
		try {
			serializer = Context.getRegisteredComponent("simpleXStreamSerializer", SimpleXStreamSerializer.class);
		}
		catch (Exception ignored) {
		}
		if (serializer == null) {
			log.debug("Not setting up XStream security as no simpleXStreamSerializer component is found");
			return;
		}
		try {
			Method method = serializer.getClass().getMethod("initXStream", XStream.class);
			method.invoke(serializer, xstream);
			log.info("XStream security initialized on ReportingSerializer");
		}
		catch (NoSuchMethodException ignored) {
			log.debug("Not setting up XStream Security as no initXStream method found on SimpleXStreamSerializer");
		}
		catch (Exception e) {
			throw new SerializationException("Failed to set up XStream Security on Reporting Serializer", e);
		}
	}
}
