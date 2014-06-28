package org.openmrs.module.reporting.evaluation.querybuilder;

import liquibase.util.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.openmrs.Cohort;
import org.openmrs.OpenmrsObject;
import org.openmrs.module.reporting.common.ObjectUtil;
import org.openmrs.module.reporting.dataset.DataSetColumn;
import org.openmrs.module.reporting.query.IdSet;
import org.openmrs.util.OpenmrsUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Helper class for building and executing an HQL query with parameters
 */
public class SqlQueryBuilder implements QueryBuilder {

	protected Log log = LogFactory.getLog(getClass());

	private List<String> queryClauses = new ArrayList<String>();
	private Map<String, Object> parameters = new HashMap<String, Object>();

	//***** CONSTRUCTORS *****

	public SqlQueryBuilder() { }

	public SqlQueryBuilder append(String clause) {
		queryClauses.add(clause);
		return this;
	}

	public SqlQueryBuilder addParameter(String parameterName, Object parameterValue) {
		boolean addParameter = true;
		if (parameterValue != null) {
			Set<Integer> memberIds = null;
			if (parameterValue instanceof Cohort) {
				memberIds = ((Cohort) parameterValue).getMemberIds();
			}
			if (parameterValue instanceof IdSet) {
				memberIds = ((IdSet) parameterValue).getMemberIds();
			}
			if (memberIds != null) {
				String toMatch = ":" + parameterName;
				for (int i=0; i<queryClauses.size(); i++) {
					String clause = queryClauses.get(i);
					if (clause.contains(toMatch)) {
						String idClause = "(" + OpenmrsUtil.join(memberIds, ",") + ")";
						clause = clause.replace("(" + toMatch + ")", idClause); // where id in (:ids)
						clause = clause.replace(toMatch, idClause); // where id in :ids
						queryClauses.set(i, clause);
						addParameter = false;
					}
				}
			}
		}
		if (addParameter) {
			getParameters().put(parameterName, normalizeParameterValue(parameterValue));
		}
		return this;
	}

	public Map<String, Object> getParameters() {
		if (parameters == null) {
			parameters = new HashMap<String, Object>();
		}
		return parameters;
	}

	public void setParameters(Map<String, Object> parameters) {
		this.parameters = parameters;
	}

	public String getSqlQuery() {
		StringBuilder sb = new StringBuilder();
		for (String clause : queryClauses) {
			sb.append(clause).append(" ");
		}
		return sb.toString();
	}

	/**
	 * Uses a Prepared Statement to produce ResultSetMetadata in order to return accurate column information
	 */
	@Override
	public List<DataSetColumn> getColumns(SessionFactory sessionFactory) {
		List<DataSetColumn> l = new ArrayList<DataSetColumn>();

		String queryString = getSqlQuery();
		queryString = StringUtils.stripComments(queryString);

		// Convert the query and the order of the parameters for use with a Prepared Statement
		Map<Integer, String> parameterIndexes = new TreeMap<Integer, String>();
		for (String parameterName : getParameters().keySet()) {
			String paramToReplace = ":" + parameterName;
			int foundIndex = queryString.indexOf(paramToReplace);
			while (foundIndex != -1) {
				parameterIndexes.put(foundIndex, parameterName);
				foundIndex = queryString.indexOf(paramToReplace, foundIndex + 1);
			}
			if (parameterIndexes.containsValue(parameterName)) {
				queryString = queryString.replace(paramToReplace, "?");
			}
		}
		queryString = queryString.replace(" in ?", " in (?)");

		List<String> parametersInOrder = new ArrayList<String>(parameterIndexes.values());

		Connection c = sessionFactory.getCurrentSession().connection();
		PreparedStatement statement = null;
		try {
			statement = c.prepareStatement(queryString);
			int nextIndex = 1;
			for (String parameterName : parametersInOrder) {
				Object parameterValue = getParameters().get(parameterName);
				statement.setObject(nextIndex++, parameterValue);
			}
			ResultSetMetaData metadata = statement.getMetaData();
			for (int i=1; i<=metadata.getColumnCount(); i++) {
				String columnName = metadata.getColumnLabel(i);
				l.add(new DataSetColumn(columnName, columnName, Object.class));
			}
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Unable to retrieve columns for query", e);
		}
		finally {
			try {
				statement.close();
			}
			catch (Exception e) {}
		}
		return l;
	}

	@Override
	public String toString() {
		String ret = getSqlQuery();
		for (String paramName : parameters.keySet()) {
			String paramVal = ObjectUtil.format(parameters.get(paramName));
			ret = ret.replace(":"+paramName, paramVal);
		}
		if (ret.length() > 500) {
			ret = ret.substring(0, 450) + " <...> " + ret.substring(ret.length() - 50);
		}
		return ret;
	}

	@Override
	public Query buildQuery(SessionFactory sessionFactory) {
		String q = getSqlQuery();
		q = StringUtils.stripComments(q);
		Query query = sessionFactory.getCurrentSession().createSQLQuery(q);
		for (Map.Entry<String, Object> e : getParameters().entrySet()) {
			Object value = normalizeParameterValue(e.getValue());
			if (value instanceof Collection) {
				query.setParameterList(e.getKey(), (Collection)value);
			}
			else {
				query.setParameter(e.getKey(), value);
			}
		}
		return query;
	}

	private Object normalizeParameterValue(Object value) {
		if (value == null) {
			return null;
		}
		Collection c = null;
		if (value instanceof Collection) {
			c = (Collection)value;
		}
		else if (value instanceof Object[]) {
			c = Arrays.asList((Object[])value);
		}
		else if (value instanceof Cohort) {
			c = ((Cohort)value).getMemberIds();
		}
		else if (value instanceof IdSet) {
			c = ((IdSet)value).getMemberIds();
		}
		if (c != null) {
			List<Object> l = new ArrayList<Object>();
			for (Object o : c) {
				l.add(normalizeParameterValue(o));
			}
			return l;
		}
		else {
			if (value instanceof OpenmrsObject) {
				return ((OpenmrsObject)value).getId();
			}
			else {
				return value;
			}
		}
	}
}
