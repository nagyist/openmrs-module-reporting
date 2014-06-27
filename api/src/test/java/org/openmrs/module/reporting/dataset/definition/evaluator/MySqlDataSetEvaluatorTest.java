package org.openmrs.module.reporting.dataset.definition.evaluator;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.openmrs.Location;
import org.openmrs.api.context.Context;
import org.openmrs.module.reporting.dataset.definition.SqlDataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.service.DataSetDefinitionService;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.querybuilder.SqlQueryBuilder;
import org.openmrs.module.reporting.evaluation.service.EvaluationService;
import org.openmrs.test.BaseModuleContextSensitiveTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Ignore
public class MySqlDataSetEvaluatorTest extends BaseModuleContextSensitiveTest {

	@Override
	public Boolean useInMemoryDatabase() {
		return false;
	}

	@Before
	public void setup() throws Exception {
		authenticate();
	}

	/**
	 * @return MS Note: use port 3306 as standard, 5538 for sandbox 5.5 mysql environment
	 */
	@Override
	public Properties getRuntimeProperties() {
		Properties p = super.getRuntimeProperties();
		p.setProperty("connection.url", "jdbc:mysql://localhost:3306/openmrs_mirebalais?autoReconnect=true&useUnicode=true&characterEncoding=UTF-8");
		return p;
	}

	@Test
	public void evaluate_shouldHandleMetadataListParameters() throws Exception {
		SqlDataSetDefinition dataSetDefinition = new SqlDataSetDefinition();
		String query = "select encounter_id, encounter_datetime, location_id from encounter where location_id in (:locations)";
		dataSetDefinition.setSqlQuery(query);

		List<Location> locationList = new ArrayList<Location>();
		locationList.add(Context.getLocationService().getLocation(1));
		locationList.add(Context.getLocationService().getLocation(3));

		EvaluationContext context = new EvaluationContext();
		context.addParameterValue("locations", locationList);

		Context.getService(DataSetDefinitionService.class).evaluate(dataSetDefinition, context);
	}

	@Test
	public void evaluate_shouldAllowAliasesWithSpaces() throws Exception {
		SqlQueryBuilder q = new SqlQueryBuilder();
		q.append("select person_id, birthdate as 'Date of Birth' from person");
		Context.getService(EvaluationService.class).evaluateToList(q, new EvaluationContext());
		Assert.assertEquals("Date of Birth", q.getColumns().get(1).getName());
	}
}
