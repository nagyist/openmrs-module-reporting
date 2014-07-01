package org.openmrs.module.reporting.evaluation.querybuilder;

import liquibase.util.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.SessionFactory;
import org.openmrs.Cohort;
import org.openmrs.OpenmrsObject;
import org.openmrs.module.reporting.common.ObjectUtil;
import org.openmrs.module.reporting.dataset.DataSetColumn;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.EvaluationProfiler;
import org.openmrs.module.reporting.query.IdSet;
import org.openmrs.util.OpenmrsUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
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
		clause = StringUtils.stripComments(clause);
		queryClauses.add(clause);
		return this;
	}

	public SqlQueryBuilder addParameter(String parameterName, Object parameterValue) {
		boolean addParameter = true;
		String toMatch = ":" + parameterName;
		if (parameterValue != null) {
			Set<Integer> memberIds = null;
			if (parameterValue instanceof Cohort) {
				memberIds = ((Cohort) parameterValue).getMemberIds();
			}
			if (parameterValue instanceof IdSet) {
				memberIds = ((IdSet) parameterValue).getMemberIds();
			}
			if (memberIds != null) {
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
		else {
			for (int i=0; i<queryClauses.size(); i++) {
				String clause = queryClauses.get(i);
				clause = clause.replace(toMatch, "null");
				queryClauses.set(i ,clause);
				addParameter = false;
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
		this.parameters.clear();
		if (parameters != null) {
			for (String parameterName : parameters.keySet()) {
				addParameter(parameterName, parameters.get(parameterName));
			}
		}
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
		PreparedStatement statement = null;
		try {
			statement = createPreparedStatement(sessionFactory.getCurrentSession().connection());
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
	public List<Object[]> evaluateToList(SessionFactory sessionFactory, EvaluationContext context) {
		List<Object[]> ret = new ArrayList<Object[]>();
		PreparedStatement statement = null;
		EvaluationProfiler profiler = new EvaluationProfiler(context);
		profiler.logBefore("EXECUTING_QUERY", toString());
		try {
			statement = createPreparedStatement(sessionFactory.getCurrentSession().connection());
			ResultSet resultSet = statement.executeQuery();
			if (resultSet != null) {
				ResultSetMetaData metaData = resultSet.getMetaData();
				while (resultSet.next()) {
					Object[] row = new Object[metaData.getColumnCount()];
					for (int i = 1; i <= metaData.getColumnCount(); i++) {
						row[i - 1] = resultSet.getObject(i);
					}
					ret.add(row);
				}
			}
		}
		catch (Exception e) {
			profiler.logError("EXECUTING_QUERY", toString(), e);
			throw new IllegalArgumentException("Unable to execute query", e);
		}
		finally {
			try {
				statement.close();
			}
			catch (Exception e) {}
		}
		profiler.logAfter("EXECUTING_QUERY", "Completed successfully with " + ret.size() + " results");
		return ret;
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

	protected PreparedStatement createPreparedStatement(Connection connection) throws SQLException {
		// Convert the query and the order of the parameters for use with a Prepared Statement
		String queryString = getSqlQuery();
		Map<Integer, String> parameterIndexes = new TreeMap<Integer, String>();
		for (String parameterName : getParameters().keySet()) {
			Object parameterValue = getParameters().get(parameterName);
			StringBuilder replacementValue = new StringBuilder("?");
			if (parameterValue instanceof Collection) {
				Collection c = (Collection)parameterValue;
				for (Iterator i = c.iterator(); i.hasNext();) {
					i.next();
					if (i.hasNext()) {
						replacementValue.append(",?");
					}
				}
			}
			String paramToReplace = ":" + parameterName;
			int foundIndex = queryString.indexOf(paramToReplace);
			while (foundIndex != -1) {
				parameterIndexes.put(foundIndex, parameterName);
				foundIndex = queryString.indexOf(paramToReplace, foundIndex + 1);
			}
			if (parameterIndexes.containsValue(parameterName)) {
				queryString = queryString.replace(paramToReplace, replacementValue);
			}
			queryString = queryString.replace(" in " + replacementValue, " in (" + replacementValue + ")");
		}

		List<String> parametersInOrder = new ArrayList<String>(parameterIndexes.values());

		PreparedStatement statement = connection.prepareStatement(queryString);
		int nextIndex = 1;
		for (String parameterName : parametersInOrder) {
			Object parameterValue = getParameters().get(parameterName);
			nextIndex = setPositionalQueryParameter(statement, nextIndex, parameterValue);
		}

		return statement;
	}

	protected int setPositionalQueryParameter(PreparedStatement statement, int position, Object value) throws SQLException {
		if (value instanceof Collection) {
			Collection c = (Collection)value;
			for (Object o : c) {
				position = setPositionalQueryParameter(statement, position, o);
			}
		}
		else {
			if (value instanceof Date) {
				statement.setTimestamp(position, new Timestamp(((Date)value).getTime()));
			}
			else if (value instanceof Integer) {
				statement.setInt(position, (Integer) value);
			}
			else if (value instanceof Short) {
				statement.setShort(position, (Short) value);
			}
			else if (value instanceof Long) {
				statement.setLong(position, (Long) value);
			}
			else if (value instanceof Float) {
				statement.setFloat(position, (Float) value);
			}
			else if (value instanceof Double) {
				statement.setDouble(position, (Double) value);
			}
			else if (value instanceof Number) {
				statement.setDouble(position, ((Number) value).doubleValue());
			}
			else if (value instanceof Boolean) {
				statement.setBoolean(position, (Boolean) value);
			}
			else {
				statement.setString(position, value.toString());
			}
			position++;
		}
		return position;
	}

	protected Object normalizeParameterValue(Object value) {
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
