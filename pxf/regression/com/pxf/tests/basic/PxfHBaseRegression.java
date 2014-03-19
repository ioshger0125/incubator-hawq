package com.pxf.tests.basic;

import java.util.ArrayList;
import java.sql.SQLWarning;

import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.FilterList.Operator;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assert;
import org.postgresql.util.PSQLException;

import com.pivotal.pxf.plugins.hbase.utilities.HBaseIntegerComparator;
import com.pivotal.pxfauto.infra.hbase.HBase;
import com.pivotal.pxfauto.infra.structures.tables.basic.Table;
import com.pivotal.pxfauto.infra.structures.tables.hbase.HBaseTable;
import com.pivotal.pxfauto.infra.structures.tables.pxf.ExternalTable;
import com.pivotal.pxfauto.infra.structures.tables.pxf.ReadableExternalTable;
import com.pivotal.pxfauto.infra.structures.tables.utils.TableFactory;
import com.pivotal.pxfauto.infra.utils.exception.ExceptionUtils;
import com.pivotal.pxfauto.infra.utils.jsystem.report.ReportUtils;
import com.pivotal.pxfauto.infra.utils.tables.ComparisonUtils;
import com.pxf.tests.dataprepares.hbase.HBaseDataPreparer;
import com.pxf.tests.fixtures.PxfHBaseFixture;
import com.pxf.tests.testcases.PxfTestCase;

public class PxfHBaseRegression extends PxfTestCase {

	final String NO_FILTER = "No filter";
	
	HBase hbase;
	int numberOfSplits = 2;

	HBaseTable hTable;
	HBaseTable hNullTable;
	HBaseTable hIntegerRowKey;
	HBaseTable lookUpTable;

	ExternalTable externalTableHbase;
	ExternalTable externalTableHBaseWithFilter;
	ReadableExternalTable externalTableFilterPrinter;

	String[] habseTableQualifiers = new String[] {
			"cf1:q1",
			"cf1:q2",
			"cf1:q3",
			"cf1:q4",
			"cf1:q5",
			"cf1:q6",
			"cf1:q7",
			"cf1:q8",
			"cf1:q9",
			"cf1:q10",
			"cf1:q11",
			"cf1:q12" };

	String[] exTableFields = new String[] {
			"recordkey TEXT",
			"\"cf1:q1\" VARCHAR",
			"\"cf1:q2\" TEXT",
			"\"cf1:q3\" INT",
			"q4 BYTEA",
			"\"cf1:q5\" REAL",
			"\"cf1:q6\" FLOAT",
			"\"cf1:q7\" BYTEA",
			"\"cf1:q8\" SMALLINT",
			"\"cf1:q9\" BIGINT",
			"\"cf1:q10\" BOOLEAN",
			"\"cf1:q11\" NUMERIC",
			"\"cf1:q12\" TIMESTAMP" };

	String[] exTableFieldsFullName = new String[] {
			"recordkey TEXT",
			"\"cf1:q1\" VARCHAR",
			"\"cf1:q2\" TEXT",
			"\"cf1:q3\" INT",
			"\"cf1:q4\" BYTEA",
			"\"cf1:q5\" REAL",
			"\"cf1:q6\" FLOAT",
			"\"cf1:q7\" BYTEA",
			"\"cf1:q8\" SMALLINT",
			"\"cf1:q9\" BIGINT",
			"\"cf1:q10\" BOOLEAN",
			"\"cf1:q11\" NUMERIC",
			"\"cf1:q12\" TIMESTAMP" };

	public PxfHBaseRegression() {
		setFixture(PxfHBaseFixture.class);
	}

	@Before
	public void defaultBefore() throws Throwable {

		ReportUtils.startLevel(report, getClass(), "setup");

		super.defaultBefore();

		hbase = (HBase) system.getSystemObject("hbase");

		hawq.runQuery("SET pxf_enable_filter_pushdown = on");

		hTable = new HBaseTable("hbase_table", new String[] { "cf1" });

		hTable.setNumberOfSplits(numberOfSplits);
		hTable.setRowKeyPrefix("row");
		hTable.setQualifiers(habseTableQualifiers);
		hTable.setRowsPerSplit(100);

		hNullTable = new HBaseTable("hbase_table_with_nulls", new String[] { "cf1" });

		hNullTable.setNumberOfSplits(numberOfSplits);
		hNullTable.setRowKeyPrefix("row");
		hNullTable.setQualifiers(habseTableQualifiers);
		hNullTable.setRowsPerSplit(5);

		hIntegerRowKey = new HBaseTable("hbase_table_integer_row_key", new String[] { "cf1" });

		hIntegerRowKey.setNumberOfSplits(numberOfSplits);
		hIntegerRowKey.setRowKeyPrefix("");
		hIntegerRowKey.setQualifiers(habseTableQualifiers);
		hIntegerRowKey.setRowsPerSplit(50);

		lookUpTable = new HBaseTable("pxflookup", new String[] { "mapping" });

		/**
		 * Create lookup table
		 */
		if (!hbase.checkTableExists(lookUpTable)) {
			hbase.createTableAndVerify(lookUpTable);
		}

		ArrayList<Put> lookUpData = new ArrayList<Put>();
		Put mapping = new Put(hTable.getName().getBytes());
		mapping.add(Bytes.toBytes("mapping"), Bytes.toBytes("q4"), Bytes.toBytes("cf1:q4"));

		lookUpData.add(mapping);

		mapping = new Put(hNullTable.getName().getBytes());
		mapping.add(Bytes.toBytes("mapping"), Bytes.toBytes("q4"), Bytes.toBytes("cf1:q4"));

		lookUpData.add(mapping);

		mapping = new Put(hIntegerRowKey.getName().getBytes());
		mapping.add(Bytes.toBytes("mapping"), Bytes.toBytes("q4"), Bytes.toBytes("cf1:q4"));

		lookUpData.add(mapping);

		lookUpTable.setRowsToGenerate(lookUpData);
		hbase.put(lookUpTable);

		lookUpTable.setQualifiers(new String[] { "mapping:q4" });

		externalTableHbase = 
				TableFactory.getPxfHbaseReadableTable("hbase_pxf_external_table", exTableFields, hTable);

		/**
		 * Create external table if not exists
		 */
		hawq.createTableAndVerify(externalTableHbase);

		ReportUtils.stopLevel(report);
	}

	/**
	 * Check Syntax validation, try to create Readable Table without PXF
	 * options, expect failure and Error message.
	 * 
	 * Create Writable Table with all options and expect success.
	 * 
	 * @throws Exception
	 */
	@Test
	public void syntaxValidation() throws Exception {

		ReportUtils.reportBold(report, getClass(), 
				"Fail to create external table directed to HBase table with no PXF paramters");

		initAndPopulateHBaseTable(hTable, false);

		ReadableExternalTable exTable = new ReadableExternalTable("pxf_extable_validations", new String[] {
				"a int",
				"b text",
				"c bytea" }, hTable.getName(), "CUSTOM");

		try {
			hawq.createTable(exTable);
		} catch (Exception e) {
			ExceptionUtils.validate(report, e, 
					new PSQLException("ERROR: Invalid URI pxf://" + exTable.getHostname() + ":" + exTable.getPort() + 
							"/" + exTable.getPath() + "?: invalid option after '?'", null), false);
		}

		ReportUtils.reportBold(report, getClass(), "Create Writable external table directed to HBase table");

		exTable = TableFactory.getPxfHbaseWritableTable("pxf_writable_extable_validations", new String[] {
				"a int",
				"b text",
				"c bytea" }, hTable);

		hawq.createTableAndVerify(exTable);
	}

	@Test
	public void analyze() throws Exception {

		ReportUtils.reportBold(report, getClass(), "Run analyze on external table with no HBase analyzer");

		initAndPopulateHBaseTable(hTable, false);

		hawq.runQueryWithExpectedWarning("ANALYZE " + externalTableHbase.getName(), 
				"PXF 'Analyzer' class was not found. Please supply it in the LOCATION clause" +
				" or use it in a PXF profile in order to run ANALYZE on this table", true);

		hawq.queryResults(externalTableHbase, 
				"SELECT relpages, reltuples FROM pg_class" +
				" WHERE relname = '" + externalTableHbase.getName() + "'");

		Table expectedTable = new Table("expected", null);

		expectedTable.addRow(new String[] { "1000", "1000000" });

		ComparisonUtils.compareTables(externalTableHbase, expectedTable, report);
	}

	@Test
	public void selectAll() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHBaseFilterTable(null);
		
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, "", NO_FILTER);
	}

	@Test
	public void selectLower() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		hawq.queryResults(externalTableHbase, 
				"SELECT cnt < 300 AS check FROM (SELECT COUNT(*) AS cnt FROM " + externalTableHbase.getName() + 
				" WHERE gp_segment_id = 0) AS a");

		Table expectedTable = new Table("expected", null);

		expectedTable.addRow(new String[] { "t" });

		ComparisonUtils.compareTables(externalTableHbase, expectedTable, report);
	}

	@Test
	public void rowRange() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE recordkey > 'row00000090' AND recordkey <= 'row00000103'";
		String filterString = "a0c\"row00000090\"o2a0c\"row00000103\"o3o7";
		
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.GREATER, 
				new BinaryComparator(Bytes.toBytes("row00000090"))));
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000103"))));

		hbase.queryResults(hTable, null);
		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHBaseFilterTable(filterString);
		
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);

	}

	@Test
	public void specificRow() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE recordkey = 'row00000100'";
		String filterString = "a0c\"row00000100\"o5";
		
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000100"))));

		hbase.queryResults(hTable, null);
		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause);

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHBaseFilterTable(filterString);
	
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void notEqualRow() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE recordkey != 'row00000090' AND recordkey <= 'row00000103'";
		String filterString = "a0c\"row00000090\"o6a0c\"row00000103\"o3o7";
		
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.NOT_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000090"))));
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000103"))));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHBaseFilterTable(filterString);
		
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void filterRowAndQualifier() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE recordkey != 'row00000090' AND recordkey <= 'row00000095' AND \"cf1:q7\" > 'o'";
		// TODO: pending adding BYTEA support to HBase filter (cf1:q7)
		//String filterString = "a0c\"row00000090\"o6a0c\"row00000095\"o3o7a7c\"o\"o2o7";
		String filterString = "a0c\"row00000090\"o6a0c\"row00000095\"o3o7";
		
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.NOT_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000090"))));
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000095"))));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q7".getBytes(), 
				CompareOp.GREATER, "o".getBytes()));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, "SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		// TODO: pending adding BYTEA support to HBase filter
		//createAndQueryPxfHBaseFilterTable(filterString);
		
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void filterSeveralQualifiers() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE \"cf1:q1\" > 'ASCII00000090' AND q4 <= 'lookup00000198'";
		String filterString = "a1c\"ASCII00000090\"o2a4c\"lookup00000198\"o3o7";
		
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q1".getBytes(), 
				CompareOp.GREATER, "ASCII00000090".getBytes()));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q4".getBytes(), 
				CompareOp.LESS_OR_EQUAL, "lookup00000198".getBytes()));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		// TODO: pending adding BYTEA support to HBase filter
		//createAndQueryPxfHBaseFilterTable(filterString);
		
		/** TODO: pending adding: 
		 * BYTEA support to filter pushdown (q4)
		 * cast between text and varchar (cf1:q1) 
		 */
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, NO_FILTER);
	}

	@Test
	public void filterTextAndNumeric() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE \"cf1:q2\" > 'UTF8_計算機用語_00000090' AND \"cf1:q3\" <= 990000";
		String filterString = "a2c\"UTF8_計算機用語_00000090\"o2a3c990000o3o7";
		
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q2".getBytes(), 
				CompareOp.GREATER, "UTF8_計算機用語_00000090".getBytes("UTF-8")));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q3".getBytes(), 
				CompareOp.LESS_OR_EQUAL, new HBaseIntegerComparator(990000L)));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		/** 
		 * TODO: failing test - UTF-8 encoding isn't passed correctly to PXF.
		 * see GPSQL-1739.
		 */
		//createAndQueryPxfHBaseFilterTable(filterString);
		//createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void filterDouble() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE \"cf1:q5\" > 91.92 AND \"cf1:q6\" <= 99999999.99";
		String filterString = NO_FILTER;
		
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q5".getBytes(), 
				CompareOp.GREATER, "91.92".getBytes()));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q6".getBytes(), 
				CompareOp.LESS_OR_EQUAL, "99999999.99".getBytes()));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void filterSmallAndBigInt() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE \"cf1:q8\" > 97 AND \"cf1:q9\" <= 9702990000000099";
		String filterString = "a8c97o2a9c9702990000000099o3o7";
		
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q8".getBytes(), CompareOp.GREATER, 
				new HBaseIntegerComparator(97L)));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q9".getBytes(), CompareOp.LESS_OR_EQUAL, 
				new HBaseIntegerComparator(9702990000000099L)));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHBaseFilterTable(filterString);
		
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void filterBigInt() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = " WHERE \"cf1:q9\" < -7000000000000000";
		String filterString = "a9c-7000000000000000o1";
		
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q9".getBytes(), CompareOp.LESS,
				new HBaseIntegerComparator(-7000000000000000L)));

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		
		createAndQueryPxfHBaseFilterTable(filterString);

		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void filterOrAnd() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		String whereClause = 
				" WHERE (((recordkey > 'row00000090') AND (recordkey <= 'row00000103'))" +
				" OR (recordkey = 'row00000105'))";
		String filterString = NO_FILTER;
		
		FilterList andFilterList = new FilterList(Operator.MUST_PASS_ALL);
		andFilterList.addFilter(new RowFilter(CompareFilter.CompareOp.GREATER, 
				new BinaryComparator(Bytes.toBytes("row00000090"))));
		andFilterList.addFilter(new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000103"))));

		FilterList allFilterList = new FilterList(Operator.MUST_PASS_ONE);

		allFilterList.addFilter(andFilterList);
		allFilterList.addFilter(new RowFilter(CompareFilter.CompareOp.EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000105"))));

		hTable.setFilters(allFilterList);

		hbase.queryResults(hTable, null);

		hawq.queryResults(externalTableHbase, 
				"SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);

		ReportUtils.startLevel(report, getClass(), "OR tests");
		
		ReportUtils.report(report, getClass(), "Query: ((x AND y) OR z). Expected filter: null");
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
		
		ReportUtils.report(report, getClass(), "Query: (x OR (y AND z)). Expected filter: null");
		whereClause = " WHERE (recordkey = 'row00000105') OR " +
				"((recordkey > 'row00000090') AND (recordkey <= 'row00000103'))";
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
		
		ReportUtils.report(report, getClass(), "Query: (x AND (y OR z)). Expected filter: x");
		whereClause = " WHERE (recordkey > 'row00000090') AND " +
				"((recordkey <= 'row00000103') OR (recordkey = 'row00000105'))";
		filterString = "a0c\"row00000090\"o2";
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
		
		ReportUtils.report(report, getClass(), "Query: (x AND (y AND z OR a)). Expected filter: x");
		whereClause = " WHERE (recordkey > 'row00000090') AND " +
				"((recordkey <= 'row00000103') AND (recordkey = 'row00000105') OR recordkey = '0')";
		filterString = "a0c\"row00000090\"o2";
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
		
		ReportUtils.stopLevel(report);
	}

	@Test
	public void filterAndNotEquals() throws Exception {

		initAndPopulateHBaseTable(hTable, false);
		
		hTable.addFilter(new RowFilter(CompareFilter.CompareOp.NOT_EQUAL, 
				new BinaryComparator(Bytes.toBytes("row00000099"))));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q8".getBytes(), 
				CompareOp.GREATER, "97".getBytes()));
		hTable.addFilter(new SingleColumnValueFilter("cf1".getBytes(), "q9".getBytes(), 
				CompareOp.LESS_OR_EQUAL, "9702990000000099".getBytes()));

		hbase.queryResults(hTable, null);

		String whereClause = 
				" WHERE recordkey != 'row00000099' AND \"cf1:q8\" > 97 AND \"cf1:q9\" <= 9702990000000099";
		String filterString = "a0c\"row00000099\"o6a8c97o2o7a9c9702990000000099o3o7";
		
		hawq.queryResults(externalTableHbase, "SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
		createAndQueryPxfHBaseFilterTable(filterString);
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
		
		whereClause = " WHERE \"cf1:q9\" <= 9702990000000099 AND recordkey != 'row00000099' AND \"cf1:q8\" > 97";
		filterString = "a9c9702990000000099o3a0c\"row00000099\"o6o7a8c97o2o7";
		
		hawq.queryResults(externalTableHbase, "SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);

		createAndQueryPxfHBaseFilterTable(filterString);
		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
		
		hawq.runQuery("SET pxf_enable_filter_pushdown = off");

		filterString = NO_FILTER;
		
		hawq.queryResults(externalTableHbase, "SELECT * FROM " + externalTableHbase.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);

		createAndQueryPxfHawqFilterTable(hTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void nullValues() throws Exception {

		initAndPopulateHBaseTable(hNullTable, true);

		String whereClause = " WHERE \"cf1:q1\" is null";
		String filterString = NO_FILTER;
		
		ExternalTable exTable = 
				TableFactory.getPxfHbaseReadableTable("habse_with_nulls_pxf_external_table", 
						exTableFields, hNullTable);

		hNullTable.addFilter(
				new SingleColumnValueFilter("cf1".getBytes(), "q1".getBytes(), CompareOp.EQUAL, "null".getBytes()));
		hbase.queryResults(hNullTable, null);

		hawq.createTableAndVerify(exTable);

		hawq.queryResults(exTable, "SELECT * FROM " + exTable.getName() + 
				whereClause + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(exTable, hNullTable, report);
		
		createAndQueryPxfHawqFilterTable(hNullTable, exTableFields, whereClause, filterString);
	}

	@Test
	public void lookupTableUpperCase() throws Exception {

		ReportUtils.reportBold(report, getClass(), 
				"Remove lower case q4 from lookup table for " + hNullTable.getName() + 
				" table and add Q4 upper case mapping");

		hbase.removeRow(lookUpTable, new String[] { hNullTable.getName() });

		ArrayList<Put> lookUpData = new ArrayList<Put>();

		Put mapping = new Put(hNullTable.getName().getBytes());

		mapping.add(Bytes.toBytes("mapping"), Bytes.toBytes("Q4"), Bytes.toBytes("cf1:q4"));

		lookUpData.add(mapping);

		lookUpTable.setQualifiers(new String[] { "mapping:q4", "mapping:Q4" });
		lookUpTable.setRowsToGenerate(lookUpData);

		hbase.put(lookUpTable);

		initAndPopulateHBaseTable(hNullTable, true);

		ExternalTable exTable = 
				TableFactory.getPxfHbaseReadableTable("habse_with_nulls_pxf_external_table", 
						exTableFields, hNullTable);

		hbase.queryResults(hNullTable, null);

		hawq.createTableAndVerify(exTable);

		hawq.queryResults(exTable, "SELECT * FROM " + exTable.getName() + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(exTable, hNullTable, report);
	}

	@Test
	public void noLookupTable() throws Exception {

		ReportUtils.reportBold(report, getClass(), 
				"Drop lookup table, fail to query external table because of q4 field resolve");

		initAndPopulateHBaseTable(hTable, false);
		initAndPopulateHBaseTable(hNullTable, true);

		hbase.dropTable(lookUpTable, false);

		try {
			hawq.queryResults(externalTableHbase, 
					"SELECT * FROM " + externalTableHbase.getName() + 
					" WHERE \"cf1:q1\" is null ORDER BY recordkey ASC");
		} catch (Exception e) {
			ExceptionUtils.validate(report, e, 
					new PSQLException("Illegal HBase column name q4, missing", null), true);
		}

		ReportUtils.reportBold(report, getClass(), 
				"Succeed to query from external table with full name for q4 field (family and qualifier)");

		ExternalTable exTableUsingFullPathQ4 = 
				TableFactory.getPxfHbaseReadableTable("habse_with_nulls_pxf_external_table", 
						exTableFieldsFullName, hNullTable);

		hNullTable.addFilter(
				new SingleColumnValueFilter("cf1".getBytes(), "q1".getBytes(), CompareOp.EQUAL, "null".getBytes()));
		hbase.queryResults(hNullTable, null);

		hawq.createTableAndVerify(exTableUsingFullPathQ4);

		hawq.queryResults(exTableUsingFullPathQ4, 
				"SELECT * FROM " + exTableUsingFullPathQ4.getName() + 
				" WHERE \"cf1:q1\" is null ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(exTableUsingFullPathQ4, hNullTable, report);

		ReportUtils.reportBold(report, getClass(), 
				"Recreate lookup table and fail to query from external table because lookup table is empty");

		hbase.createTableAndVerify(lookUpTable);

		try {
			hawq.queryResults(externalTableHbase, 
					"SELECT recordkey, \"cf1:q1\" FROM " + externalTableHbase.getName() + 
					" ORDER BY recordkey LIMIT 5;");
		} catch (Exception e) {
			ExceptionUtils.validate(report, e, 
					new PSQLException("Illegal HBase column name q4, missing", null), true);
		}

		ReportUtils.reportBold(report, getClass(), "Succeed to query external table with full q4 field name");

		hawq.queryResults(exTableUsingFullPathQ4, 
				"SELECT * FROM " + exTableUsingFullPathQ4.getName() + 
				" WHERE \"cf1:q1\" is null ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHbase, hTable, report);
	}

	@Test
	public void disableLookupTable() throws Exception {

		ReportUtils.reportBold(report, getClass(), 
				"Disable lookup table and succeed to query external table with full column name");

		initAndPopulateHBaseTable(hTable, false);

		hbase.disableTable(lookUpTable);

		ExternalTable exTableUsingFullPathQ4 = 
				TableFactory.getPxfHbaseReadableTable("habse_lookup_external_table", exTableFieldsFullName, hTable);

		hTable.setQualifiers(new String[] { "cf1:q1" });

		hbase.queryResults(hTable, null);

		hawq.createTableAndVerify(exTableUsingFullPathQ4);

		hawq.queryResults(exTableUsingFullPathQ4, 
				"SELECT recordkey, \"cf1:q1\" FROM " + exTableUsingFullPathQ4.getName() + 
				" ORDER BY recordkey;");

		ComparisonUtils.compareTables(exTableUsingFullPathQ4, hTable, report);

		hbase.enableTable(lookUpTable);
	}

	@Test
	public void removeColumnFromLookupTable() throws Exception {

		ReportUtils.reportBold(report, getClass(), 
				"Remove lookup table 'Mapping' column family" +
				" and succeed to query external table with full column name");

		hbase.removeColumn(lookUpTable, new String[] { "mapping" });

		initAndPopulateHBaseTable(hTable, false);

		ExternalTable exTableUsingFullPathQ4 = 
				TableFactory.getPxfHbaseReadableTable("habse_lookup_external_table", exTableFieldsFullName, hTable);

		hTable.setQualifiers(new String[] { "cf1:q1" });

		hbase.queryResults(hTable, null);

		hawq.createTableAndVerify(exTableUsingFullPathQ4);

		hawq.queryResults(exTableUsingFullPathQ4, 
				"SELECT recordkey, \"cf1:q1\" FROM " + exTableUsingFullPathQ4.getName() + 
				" ORDER BY recordkey;");

		ComparisonUtils.compareTables(exTableUsingFullPathQ4, hTable, report);

		hbase.addColumn(lookUpTable, new String[] { "mapping" });
	}

	@Test
	public void recordKeyAsInteger() throws Exception {

		String[] recordkeyIntegerFields = new String[] {
				"recordkey INTEGER",
				"\"cf1:q1\" TEXT",
				"\"cf1:q2\" TEXT",
				"\"cf1:q3\" INT",
				"\"cf1:q4\" BYTEA",
				"\"cf1:q5\" REAL",
				"\"cf1:q6\" FLOAT",
				"\"cf1:q7\" BYTEA",
				"\"cf1:q8\" SMALLINT",
				"\"cf1:q9\" BIGINT",
				"\"cf1:q10\" BOOLEAN",
				"\"cf1:q11\" NUMERIC",
				"\"cf1:q12\" TIMESTAMP"	
		};
		
		initAndPopulateHBaseTable(hIntegerRowKey, false);

		String whereClause = " WHERE recordkey = 50";
		String filterString = "a0c50o5";
		hIntegerRowKey.addFilter(new RowFilter(CompareFilter.CompareOp.EQUAL, 
				new BinaryComparator(Bytes.toBytes("00000050"))));
		hbase.queryResults(hIntegerRowKey, null);

		ExternalTable exTableIntegerRowKey = 
				TableFactory.getPxfHbaseReadableTable("habse_integer_row_key_external_table", 
						recordkeyIntegerFields, hIntegerRowKey);

		hawq.createTableAndVerify(exTableIntegerRowKey);
		
		hawq.queryResults(exTableIntegerRowKey, 
				"SELECT * FROM " + exTableIntegerRowKey.getName() + whereClause);

		ComparisonUtils.compareTables(exTableIntegerRowKey, hIntegerRowKey, report);

		createAndQueryPxfHBaseFilterTable(filterString, hIntegerRowKey, recordkeyIntegerFields);
		
		createAndQueryPxfHawqFilterTable(hIntegerRowKey, recordkeyIntegerFields, whereClause, filterString);
		
		whereClause = " WHERE recordkey <= 30 OR recordkey > 145";
		filterString = NO_FILTER;
		FilterList orFilter = new FilterList(Operator.MUST_PASS_ONE);
		orFilter.addFilter(new RowFilter(CompareFilter.CompareOp.LESS_OR_EQUAL, 
				new BinaryComparator(Bytes.toBytes("00000030"))));
		orFilter.addFilter(new RowFilter(CompareFilter.CompareOp.GREATER, 
				new BinaryComparator(Bytes.toBytes("00000145"))));

		hIntegerRowKey.addFilter(orFilter);
		hbase.queryResults(hIntegerRowKey, null);

		hawq.queryResults(exTableIntegerRowKey, 
				"SELECT * FROM " + exTableIntegerRowKey.getName() + 
				whereClause + " ORDER BY recordkey;");

		ComparisonUtils.compareTables(exTableIntegerRowKey, hIntegerRowKey, report);
		
		createAndQueryPxfHawqFilterTable(hIntegerRowKey, recordkeyIntegerFields, whereClause, filterString);
	}

	@Test
	public void notExistsHBaseTable() throws Exception {

		ReadableExternalTable notExistsTable = 
				TableFactory.getPxfHbaseReadableTable("not_exists_hbase_table", exTableFields, 
						new HBaseTable("not_exists_hbase_table", null));

		hawq.createTableAndVerify(notExistsTable);

		try {
			hawq.queryResults(notExistsTable, "SELECT * FROM " + notExistsTable.getName() + " WHERE recordkey = 50");
		} catch (Exception e) {
			ExceptionUtils.validate(report, e, 
					new PSQLException("org.apache.hadoop.hbase.TableNotFoundException: not_exists_hbase_table", null), true);
		}
	}

	@Test
	public void emptyHBaseTable() throws Exception {

		HBaseTable emptyTable = new HBaseTable("empty_table", new String[] { "cf1" });

		hbase.createTableAndVerify(emptyTable);

		ReadableExternalTable exTable = 
				TableFactory.getPxfHbaseReadableTable("empty_hbase_table", exTableFieldsFullName, emptyTable);

		hawq.createTableAndVerify(exTable);
		hawq.queryResults(exTable, "SELECT * FROM " + exTable.getName());

		ComparisonUtils.compareTables(exTable, emptyTable, report);
	}

	/**
	 * Making sure the required table is exists, if not creating it and populate
	 * it.
	 * 
	 * @param table
	 * @param useNullsInData
	 * @throws Exception
	 */
	private void initAndPopulateHBaseTable(HBaseTable table, boolean useNullsInData)
			throws Exception {

		ReportUtils.startLevel(report, getClass(), "Init and populate HBase table: " + table.getName());

		if (!hbase.checkTableExists(table)) {

			hbase.createTableAndVerify(table);

			HBaseDataPreparer dataPreparer = new HBaseDataPreparer();
			dataPreparer.setColumnFamilyName(table.getFields()[0]);
			dataPreparer.setNumberOfSplits(numberOfSplits);
			dataPreparer.setRowKeyPrefix(table.getRowKeyPrefix());
			dataPreparer.setUseNull(useNullsInData);
			dataPreparer.prepareData(table.getRowsPerSplit(), table);

			hbase.put(table);
		}

		ReportUtils.stopLevel(report);
	}

	/**
	 * Creates and queries table using HBaseAccessorWithAccessor.
	 * Table fields are {@link exTableFields}, 
	 * HBase table in the path is {@link hTable}, and is also used for comparing query results.
	 * 
	 * @param filter serialized filter string to be used in table created.
	 * @throws Exception
	 */
	private void createAndQueryPxfHBaseFilterTable(String filter) throws Exception {
		createAndQueryPxfHBaseFilterTable(filter, hTable, exTableFields);
	}
	
	/**
	 * Creates and queries table using HBaseAccessorWithAccessor.
	 * 
	 * @param filter serialized filter string to be used in table created.
	 * @param hbaseTable HBase table used in the table's path and for comparing query results.
	 * @param fields table's fields.
	 * @throws Exception
	 */
	private void createAndQueryPxfHBaseFilterTable(String filter, 
			HBaseTable hbaseTable, String[] fields) throws Exception {
		
		ReportUtils.report(report, getClass(), "running query on HBaseAccessorWithFilter, filter: " + filter);
		
		createPxfHBaseFilterTable(filter, hbaseTable, fields);
		
		hawq.queryResults(externalTableHBaseWithFilter, "SELECT * FROM " + externalTableHBaseWithFilter.getName() + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(externalTableHBaseWithFilter, hbaseTable, report);
	}
	
	/**
	 * Creates PXF HBase table using HBaseAccessorWithFilter.
	 * 
	 * @param filter serialized filter string to be used in table created.
	 * @param hbaseTable HBase table to be used in the table's path.
	 * @param fields table's fields.
	 * @throws Exception
	 */
	private void createPxfHBaseFilterTable(String filter, HBaseTable hbaseTable, String[] fields) throws Exception {
		
		externalTableHBaseWithFilter = new ReadableExternalTable("hbase_pxf_with_filter", fields, hbaseTable.getName(), "CUSTOM"); 
		externalTableHBaseWithFilter.setFragmenter("com.pivotal.pxf.plugins.hbase.HBaseDataFragmenter");
		externalTableHBaseWithFilter.setAccessor("HBaseAccessorWithFilter");
		externalTableHBaseWithFilter.setResolver("com.pivotal.pxf.plugins.hbase.HBaseResolver");
		externalTableHBaseWithFilter.setFormatter("pxfwritable_import");

		externalTableHBaseWithFilter.setUserParameters(new String[] { "TEST-HBASE-FILTER=" + filter});
			
		hawq.createTableAndVerify(externalTableHBaseWithFilter);
	}
	
	private void createAndQueryPxfHawqFilterTable(HBaseTable hbaseTable, String[] fields, 
			String whereClause, String expectedFilter) throws Exception {
		
		ReportUtils.report(report, getClass(), "running query on FilterPrinterAccessor " +
				"with WHERE: clause " + whereClause + ", expected filter: " + expectedFilter);
		
		externalTableFilterPrinter = new ReadableExternalTable("hbase_pxf_print_filter", fields, hbaseTable.getName(), "CUSTOM"); 
		externalTableFilterPrinter.setFragmenter("com.pivotal.pxf.plugins.hbase.HBaseDataFragmenter");
		externalTableFilterPrinter.setAccessor("FilterPrinterAccessor");
		externalTableFilterPrinter.setResolver("com.pivotal.pxf.plugins.hbase.HBaseResolver");
		externalTableFilterPrinter.setFormatter("pxfwritable_import");
			
		hawq.createTableAndVerify(externalTableFilterPrinter);
		
		try {
			hawq.queryResults(externalTableFilterPrinter, 
					"SELECT * FROM " + externalTableFilterPrinter.getName() + 
					" " + whereClause + " ORDER BY recordkey ASC");
		} catch (Exception e) {
			ExceptionUtils.validate(report, e, new Exception("ERROR.*Filter string: '" + expectedFilter + "'.*"), true, true);
		}
	}

	@Test
	public void selectAllDeprecatedClasses() throws Exception {

		initAndPopulateHBaseTable(hTable, false);

		hbase.queryResults(hTable, null);

		ExternalTable table = new ReadableExternalTable("hbase_pxf_with_deprecated", 
														exTableFields, hTable.getName(), 
														"CUSTOM");
		table.setFragmenter("HBaseDataFragmenter");
		table.setAccessor("HBaseAccessor");
		table.setResolver("HBaseResolver");
		table.setFormatter("pxfwritable_import");

		try {
			hawq.createTableAndVerify(table);
			Assert.fail("A SQLWarning should have been thrown");
		} catch (SQLWarning warnings) {
			SQLWarning warning = warnings;
			assertUseIsDeprecated("HBaseDataFragmenter", warning);
			warning = warning.getNextWarning();
			assertUseIsDeprecated("HBaseAccessor", warning);
			warning = warning.getNextWarning();
			assertUseIsDeprecated("HBaseResolver", warning);
			warning = warning.getNextWarning();
			Assert.assertNull(warning);
		}

		// TODO once jsystem-infra supports throwing warnings from queryResults
		// check warnings are also printed here
		hawq.queryResults(table, 
				"SELECT * FROM " + table.getName() + " ORDER BY recordkey ASC");

		ComparisonUtils.compareTables(table, hTable, report);
	}

	private void assertUseIsDeprecated(String classname, SQLWarning warning)
	{
		Assert.assertEquals("Use of " + classname + 
							" is deprecated and it will be removed on the next major version",
							warning.getMessage());
	}
}
