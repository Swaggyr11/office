package com.dqc.breaks

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.Row
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object BreaksDQCJob {

  // ──────────────────────────────────────────────────────────────
  // TABLE / VIEW CONSTANTS
  // ──────────────────────────────────────────────────────────────
  val FACT_TABLE    = "gfolyrck_managed.om_rck_ledger_break_dtl_fact"
  val FACT_VIEW     = "gfolyrck_managed.om_rck_ledger_break_dtl_fact_view"
  val SOURCE_STATUS = "RECONMGMT.BREAKS_HIVE_LOAD_STATUS_SUMMARY"
  val OUTPUT_TABLE  = "gfolyrck_managed.om_rck_data_quality_chk_tbl"
  val THEME         = "Breaks"
  val CRTD_BY       = "DQC_BREAKS_JOB"

  // FX Rate currency codes excluded from null check as per spec
  val FX_RATE_EXCLUSIONS = Seq("ACU", "GOZ", "PLL", "NUL", "XAA", "FOZ")

  def main(args: Array[String]): Unit = {

    // ──────────────────────────────────────────────────────────────
    // STEP 1 – INITIALISE SPARK SESSION
    // ──────────────────────────────────────────────────────────────
    val spark = SparkSession.builder()
      .appName("DQC_BREAKS_Job")
      .enableHiveSupport()
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    // ──────────────────────────────────────────────────────────────
    // STEP 2 – DERIVE DATES
    // dwh_business_date = T-1 in INT format e.g. 20260526
    // run_date          = today in STRING format e.g. 2026-05-27
    // run_time          = exact timestamp of execution
    // ──────────────────────────────────────────────────────────────
    val runDateTime = LocalDateTime.now()
    val runTime     = Timestamp.valueOf(runDateTime)
    val runDate     = runDateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    // T-1 business date – passed as argument e.g. 20260526
    // If not passed, auto-derives yesterday in YYYYMMDD INT format
    val dwhBusinessDate: Int = if (args.nonEmpty) {
      args(0).toInt
    } else {
      runDateTime.minusDays(1)
        .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        .toInt
    }

    println(s"[$THEME DQC] Starting job | DWH_BUSINESS_DATE: $dwhBusinessDate | RUN_DATE: $runDate | RUN_TIME: $runTime")

    // ──────────────────────────────────────────────────────────────
    // STEP 3 – FETCH DISTINCT GRANULARITY COMBINATIONS
    // Table granularity: dwh_business_date + instance + row_type + cluster_type
    // Fetch distinct instance + row_type + cluster_type for T-1 date
    // ──────────────────────────────────────────────────────────────
    val granularityRows = spark.sql(
      s"""
         |SELECT DISTINCT
         |    instance,
         |    row_type,
         |    cluster_type
         |FROM $FACT_TABLE
         |WHERE dwh_business_date = $dwhBusinessDate
      """.stripMargin
    ).collect()

    println(s"[$THEME DQC] Found ${granularityRows.length} distinct combinations to process")

    if (granularityRows.isEmpty) {
      println(s"[$THEME DQC] WARNING: No data found in $FACT_TABLE for dwh_business_date = $dwhBusinessDate. Exiting.")
      spark.stop()
      return
    }

    // ──────────────────────────────────────────────────────────────────────────
    // OPTIMISATION – PRE-COMPUTE ALL COUNTS IN 3 BULK QUERIES
    //
    // Original code fired up to N x 22 individual runCount() Spark SQL queries
    // (one per check per granularity row).  Each query caused a full or partial
    // table scan.  Instead we run:
    //   • factAggMap  – one GROUP BY on FACT_TABLE  (checks 1,2,3,11,13,15,18–22)
    //   • viewAggMap  – one GROUP BY on FACT_VIEW   (checks 4–8,12,14,17)
    //   • sourceMap   – one GROUP BY on SOURCE_STATUS (check 1 source side)
    //
    // Results are collected into Maps keyed by (instance, row_type, cluster_type)
    // so the per-granularity loop below does pure in-memory lookups – zero
    // additional Spark jobs inside the loop.
    // ──────────────────────────────────────────────────────────────────────────

    val fxExclusionList = FX_RATE_EXCLUSIONS.map(c => s"'$c'").mkString(",")

    // ── FACT TABLE bulk aggregation ───────────────────────────────────────────
    // Covers: completeness target count, duplicate check, and all FACT TABLE
    // null checks (LVID, ownership_id, currency, statement_date, instance,
    // set_id, corr_acc_no, bran_code, agent_code).
    //
    // Duplicate is inlined via a window COUNT so no extra scan is needed.
    val factAggMap: Map[(String,String,String), Row] = spark.sql(
      s"""
         |SELECT
         |    instance,
         |    row_type,
         |    cluster_type,
         |    COUNT(*)                                                                                    AS target_count,
         |    SUM(CASE WHEN dup_flag > 1 THEN 1 ELSE 0 END)                                             AS duplicate_count,
         |    SUM(CASE WHEN lvid          IS NULL OR TRIM(lvid)                          = '' THEN 1 ELSE 0 END) AS lvid_null_cnt,
         |    SUM(CASE WHEN ownership_id  IS NULL OR TRIM(CAST(ownership_id  AS STRING)) = '' THEN 1 ELSE 0 END) AS ownership_id_null_cnt,
         |    SUM(CASE WHEN local_currency IS NULL OR TRIM(local_currency)               = '' THEN 1 ELSE 0 END) AS currency_null_cnt,
         |    SUM(CASE WHEN statement_date IS NULL OR TRIM(CAST(statement_date AS STRING)) = '' THEN 1 ELSE 0 END) AS statement_date_null_cnt,
         |    SUM(CASE WHEN instance       IS NULL OR TRIM(instance)                     = '' THEN 1 ELSE 0 END) AS instance_null_cnt,
         |    SUM(CASE WHEN set_id         IS NULL OR TRIM(CAST(set_id         AS STRING)) = '' THEN 1 ELSE 0 END) AS set_id_null_cnt,
         |    SUM(CASE WHEN corr_acc_no    IS NULL OR TRIM(CAST(corr_acc_no    AS STRING)) = '' THEN 1 ELSE 0 END) AS corr_acc_no_null_cnt,
         |    SUM(CASE WHEN bran_code      IS NULL OR TRIM(CAST(bran_code      AS STRING)) = '' THEN 1 ELSE 0 END) AS bran_code_null_cnt,
         |    SUM(CASE WHEN agent_code     IS NULL OR TRIM(CAST(agent_code     AS STRING)) = '' THEN 1 ELSE 0 END) AS agent_code_null_cnt
         |FROM (
         |    SELECT
         |        instance, row_type, cluster_type,
         |        lvid, ownership_id, local_currency, statement_date,
         |        set_id, corr_acc_no, bran_code, agent_code,
         |        COUNT(*) OVER (
         |            PARTITION BY item_id, instance, dwh_business_date, row_type
         |        ) AS dup_flag
         |    FROM $FACT_TABLE
         |    WHERE dwh_business_date = $dwhBusinessDate
         |) t
         |GROUP BY instance, row_type, cluster_type
      """.stripMargin
    ).collect().map { r =>
      (r.getAs[String]("instance"),
       r.getAs[String]("row_type"),
       r.getAs[String]("cluster_type")) -> r
    }.toMap

    // ── FACT VIEW bulk aggregation ────────────────────────────────────────────
    // Covers: lvid_region, lvid_description, lvid_country, rec_type, rec_type_2,
    //         fx_rate (with exclusions), item_value_date, ownership_hierarchy.
    val viewAggMap: Map[(String,String,String), Row] = spark.sql(
      s"""
         |SELECT
         |    instance,
         |    row_type,
         |    cluster_type,
         |    SUM(CASE WHEN lvid_region      IS NULL OR TRIM(lvid_region)      = '' THEN 1 ELSE 0 END) AS lvid_region_null_cnt,
         |    SUM(CASE WHEN lvid_description IS NULL OR TRIM(lvid_description) = '' THEN 1 ELSE 0 END) AS lvid_desc_null_cnt,
         |    SUM(CASE WHEN lvid_country     IS NULL OR TRIM(lvid_country)     = '' THEN 1 ELSE 0 END) AS lvid_country_null_cnt,
         |    SUM(CASE WHEN rec_type         IS NULL OR TRIM(rec_type)         = '' THEN 1 ELSE 0 END) AS rec_type_null_cnt,
         |    SUM(CASE WHEN rec_type_2       IS NULL OR TRIM(rec_type_2)       = '' THEN 1 ELSE 0 END) AS rec_type_2_null_cnt,
         |    SUM(CASE
         |            WHEN (fx_rate IS NULL OR TRIM(CAST(fx_rate AS STRING)) = '')
         |             AND UPPER(TRIM(currency)) NOT IN ($fxExclusionList)
         |            THEN 1 ELSE 0
         |        END)                                                                   AS fx_rate_null_cnt,
         |    SUM(CASE WHEN item_value_date IS NULL OR TRIM(CAST(item_value_date AS STRING)) = '' THEN 1 ELSE 0 END) AS value_date_null_cnt,
         |    SUM(CASE
         |            WHEN ownership_id IS NOT NULL
         |             AND (
         |                  l1_group IS NULL OR l1_owner IS NULL OR
         |                  l2_group IS NULL OR l2_owner IS NULL OR
         |                  l3_group IS NULL OR l3_owner IS NULL OR
         |                  l4_group IS NULL OR l4_owner IS NULL OR
         |                  l5_group IS NULL OR l5_owner IS NULL OR
         |                  l6_group IS NULL OR l6_owner IS NULL OR
         |                  l7_group IS NULL OR l7_owner IS NULL OR
         |                  l8_group IS NULL OR l8_owner IS NULL
         |             )
         |            THEN 1 ELSE 0
         |        END)                                                                   AS ownership_hierarchy_null_cnt
         |FROM $FACT_VIEW
         |WHERE dwh_business_date = $dwhBusinessDate
         |GROUP BY instance, row_type, cluster_type
      """.stripMargin
    ).collect().map { r =>
      (r.getAs[String]("instance"),
       r.getAs[String]("row_type"),
       r.getAs[String]("cluster_type")) -> r
    }.toMap

    // ── SOURCE STATUS counts (for completeness check) ─────────────────────────
    // FIX: original compared source count (cluster_type only) against target
    // count (cluster_type + instance + row_type) – apples vs oranges.
    // Source table has no instance/row_type columns so source is kept at
    // cluster_type level; completeness is Green when target >= source.
    val sourceMap: Map[String, Long] = spark.sql(
      s"""
         |SELECT
         |    cluster_type,
         |    COUNT(*) AS source_count
         |FROM $SOURCE_STATUS
         |WHERE dwh_business_date = $dwhBusinessDate
         |  AND status            = 'Completed'
         |GROUP BY cluster_type
      """.stripMargin
    ).collect().map { r =>
      r.getAs[String]("cluster_type") -> r.getAs[Long]("source_count")
    }.toMap

    // ──────────────────────────────────────────────────────────────
    // STEP 4 – LOOP OVER EACH COMBINATION AND EXECUTE ALL CHECKS
    // All checks now resolve via in-memory map lookups – no Spark
    // jobs are triggered inside this loop.
    // ──────────────────────────────────────────────────────────────
    val results: Seq[Row] = granularityRows.toSeq.map { granRow =>

      val instance    = granRow.getAs[String]("instance")
      val rowType     = granRow.getAs[String]("row_type")
      val clusterType = granRow.getAs[String]("cluster_type")
      val key         = (instance, rowType, clusterType)

      println(s"[$THEME DQC] Processing → instance: $instance | row_type: $rowType | cluster_type: $clusterType")

      // ── helper: Green if count == 0, Red otherwise ────────────────────────
      def greenIfZero(count: Long): String = if (count == 0L) "Green" else "Red"

      // ── retrieve pre-computed rows ────────────────────────────────────────
      val fRow = factAggMap(key)
      val vRow = viewAggMap(key)

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 1: COMPLETENESS COUNT
      // Source: RECONMGMT.BREAKS_HIVE_LOAD_STATUS_SUMMARY
      //         filtered by dwh_business_date + cluster_type + status=Completed
      // Target: om_rck_ledger_break_dtl_fact
      //         filtered by dwh_business_date + cluster_type + instance + row_type
      // completeness_count stores the SOURCE count for reference
      // Green if source count <= target count, Red otherwise
      // ──────────────────────────────────────────────────────────────────────
      val sourceCount        = sourceMap.getOrElse(clusterType, 0L)
      val targetCount        = fRow.getAs[Long]("target_count")
      val completenessCount  = sourceCount.toInt
      val completenessStatus = if (targetCount >= sourceCount) "Green" else "Red"

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 2: DUPLICATE CHECK
      // Combination of item_id + instance + dwh_business_date + row_type
      // must be unique – count duplicates (groups with count > 1)
      // Source: FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val duplicateCount = fRow.getAs[Long]("duplicate_count")
      val duplicateCheck = greenIfZero(duplicateCount)

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 3: LVID NULL CHECK
      // lvid should not be NULL in FACT TABLE for given business date
      // ──────────────────────────────────────────────────────────────────────
      val lvidNullCheck = greenIfZero(fRow.getAs[Long]("lvid_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 4: LVID_REGION NULL CHECK  – Done on VIEW
      // ──────────────────────────────────────────────────────────────────────
      val lvidRegionNullCheck = greenIfZero(vRow.getAs[Long]("lvid_region_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 5: LVID_DESCRIPTION NULL CHECK  – Done on VIEW
      // ──────────────────────────────────────────────────────────────────────
      val lvidDescNullCheck = greenIfZero(vRow.getAs[Long]("lvid_desc_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 6: LVID_COUNTRY NULL CHECK  – Done on VIEW
      // ──────────────────────────────────────────────────────────────────────
      val lvidCountryNullCheck = greenIfZero(vRow.getAs[Long]("lvid_country_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 7: REC_TYPE NULL CHECK  – Done on VIEW
      // ──────────────────────────────────────────────────────────────────────
      val recTypeNullCheck = greenIfZero(vRow.getAs[Long]("rec_type_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 8: REC_TYPE_2 NULL CHECK  – Done on VIEW
      // ──────────────────────────────────────────────────────────────────────
      val recType2NullCheck = greenIfZero(vRow.getAs[Long]("rec_type_2_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 9: SOE_ID – NOT APPLICABLE FOR BREAKS
      // ──────────────────────────────────────────────────────────────────────
      val soeIdNullCheck = "N/A"

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 10: USER_HIERARCHY – NOT APPLICABLE FOR BREAKS
      // ──────────────────────────────────────────────────────────────────────
      val userHierarchyNullCheck = "N/A"

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 11: OWNERSHIP_ID NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val ownershipIdNullCheck = greenIfZero(fRow.getAs[Long]("ownership_id_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 12: OWNERSHIP_HIERARCHY NULL CHECK
      // Done on VIEW – only for records where ownership_id IS NOT NULL
      // Checks all L1 to L8 group and owner columns
      // ──────────────────────────────────────────────────────────────────────
      val ownershipHierarchyNullCheck = greenIfZero(vRow.getAs[Long]("ownership_hierarchy_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 13: CURRENCY NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val currencyNullCheck = greenIfZero(fRow.getAs[Long]("currency_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 14: FX_RATE NULL CHECK
      // Done on VIEW
      // Exclude known currencies with no FX rate: ACU,GOZ,PLL,NUL,XAA,FOZ
      // ──────────────────────────────────────────────────────────────────────
      val fxRateNullCheck = greenIfZero(vRow.getAs[Long]("fx_rate_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 15: STATEMENT_DATE NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val statementDateNullCheck = greenIfZero(fRow.getAs[Long]("statement_date_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 16: ACTION_DATE – NOT APPLICABLE FOR BREAKS
      // ──────────────────────────────────────────────────────────────────────
      val actionDateNullCheck = "N/A"

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 17: VALUE_DATE NULL CHECK
      // item_value_date should not be NULL
      // Done on VIEW
      // ──────────────────────────────────────────────────────────────────────
      val valueDateNullCheck = greenIfZero(vRow.getAs[Long]("value_date_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 18: INSTANCE NULL CHECK
      // Check for NULL instance name in FACT TABLE for the business date
      // FIX: original query was missing AND instance = '$instance' filter,
      //      causing it to scan all instances. Now scoped via GROUP BY.
      // ──────────────────────────────────────────────────────────────────────
      val instanceNullCheck = greenIfZero(fRow.getAs[Long]("instance_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 19: SET_ID NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val setIdNullCheck = greenIfZero(fRow.getAs[Long]("set_id_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 20: CORR_ACC_NO NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val corrAccNoNullCheck = greenIfZero(fRow.getAs[Long]("corr_acc_no_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 21: DEPARTMENT / BRAN_CODE NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val branCodeNullCheck = greenIfZero(fRow.getAs[Long]("bran_code_null_cnt"))

      // ──────────────────────────────────────────────────────────────────────
      // CHECK 22: AGENT_CODE NULL CHECK  – Done on FACT TABLE
      // ──────────────────────────────────────────────────────────────────────
      val agentCodeNullCheck = greenIfZero(fRow.getAs[Long]("agent_code_null_cnt"))

      // ── DERIVE OVERALL CURRENT_STATUS ─────────────────────────────────────
      // Collect all check results – exclude N/A checks
      // If any check is Red → Open, else → No_Issue
      val allCheckResults = Seq(
        "Completeness"        -> completenessStatus,
        "Duplicate"           -> duplicateCheck,
        "LVID"                -> lvidNullCheck,
        "LVID_Region"         -> lvidRegionNullCheck,
        "LVID_Description"    -> lvidDescNullCheck,
        "LVID_Country"        -> lvidCountryNullCheck,
        "Rec_Type"            -> recTypeNullCheck,
        "Rec_Type_2"          -> recType2NullCheck,
        "Ownership_ID"        -> ownershipIdNullCheck,
        "Ownership_Hierarchy" -> ownershipHierarchyNullCheck,
        "Currency"            -> currencyNullCheck,
        "FX_Rate"             -> fxRateNullCheck,
        "Statement_Date"      -> statementDateNullCheck,
        "Value_Date"          -> valueDateNullCheck,
        "Instance"            -> instanceNullCheck,
        "Set_ID"              -> setIdNullCheck,
        "Corr_Acc_No"         -> corrAccNoNullCheck,
        "Bran_Code"           -> branCodeNullCheck,
        "Agent_Code"          -> agentCodeNullCheck
      )

      val failedChecks  = allCheckResults.filter(_._2 == "Red").map(_._1)
      val currentStatus = if (failedChecks.nonEmpty) "Open" else "No_Issue"

      // Auto-generate comments listing failed checks
      val comments =
        if (failedChecks.isEmpty) "All checks passed"
        else s"Failed checks: ${failedChecks.mkString(", ")}"

      println(s"[$THEME DQC] instance=$instance | row_type=$rowType | cluster_type=$clusterType | status=$currentStatus")

      // ── BUILD OUTPUT ROW ──────────────────────────────────────────────────
      // Column order must match output table DDL exactly
      Row(
        THEME,                       // theme
        "",                          // kpi_id – populate if needed
        FACT_TABLE,                  // issue_object
        dwhBusinessDate,             // dwh_business_date (INT)
        instance,                    // instance
        rowType,                     // row_type
        clusterType,                 // cluster_type
        completenessCount,           // completeness_count (INT)
        duplicateCheck,              // duplicate_check
        lvidNullCheck,               // lvid_null_check
        lvidRegionNullCheck,         // lvid_region_null_check
        lvidDescNullCheck,           // lvid_description_null_check
        lvidCountryNullCheck,        // lvid_country_null_check
        recTypeNullCheck,            // rec_type_null_check
        recType2NullCheck,           // rec_type_2_null_check
        soeIdNullCheck,              // soe_id_null_check
        userHierarchyNullCheck,      // user_hierarchy_null_check
        ownershipIdNullCheck,        // ownership_id_null_check
        ownershipHierarchyNullCheck, // ownership_hierarchy_null_check
        currencyNullCheck,           // currency_null_check
        fxRateNullCheck,             // fx_rate_null_check
        statementDateNullCheck,      // statement_date_null_check
        actionDateNullCheck,         // action_date_null_check
        valueDateNullCheck,          // value_date_null_check
        instanceNullCheck,           // instance_null_check
        setIdNullCheck,              // set_id_null_check
        corrAccNoNullCheck,          // corr_acc_no_null_check
        branCodeNullCheck,           // bran_code_null_check
        agentCodeNullCheck,          // agent_code_null_check
        comments,                    // comments
        currentStatus,               // current_status
        runTime,                     // run_time
        runDate,                     // run_date
        CRTD_BY                      // crtd_by
      )
    }

    // ──────────────────────────────────────────────────────────────
    // STEP 5 – CREATE DATAFRAME SCHEMA MATCHING OUTPUT TABLE DDL
    // ──────────────────────────────────────────────────────────────
    import org.apache.spark.sql.types._

    val schema = StructType(Seq(
      StructField("theme",                          StringType,    nullable = true),
      StructField("kpi_id",                         StringType,    nullable = true),
      StructField("issue_object",                   StringType,    nullable = true),
      StructField("dwh_business_date",              IntegerType,   nullable = true),
      StructField("instance",                       StringType,    nullable = true),
      StructField("row_type",                       StringType,    nullable = true),
      StructField("cluster_type",                   StringType,    nullable = true),
      StructField("completeness_count",             IntegerType,   nullable = true),
      StructField("duplicate_check",                StringType,    nullable = true),
      StructField("lvid_null_check",                StringType,    nullable = true),
      StructField("lvid_region_null_check",         StringType,    nullable = true),
      StructField("lvid_description_null_check",    StringType,    nullable = true),
      StructField("lvid_country_null_check",        StringType,    nullable = true),
      StructField("rec_type_null_check",            StringType,    nullable = true),
      StructField("rec_type_2_null_check",          StringType,    nullable = true),
      StructField("soe_id_null_check",              StringType,    nullable = true),
      StructField("user_hierarchy_null_check",      StringType,    nullable = true),
      StructField("ownership_id_null_check",        StringType,    nullable = true),
      StructField("ownership_hierarchy_null_check", StringType,    nullable = true),
      StructField("currency_null_check",            StringType,    nullable = true),
      StructField("fx_rate_null_check",             StringType,    nullable = true),
      StructField("statement_date_null_check",      StringType,    nullable = true),
      StructField("action_date_null_check",         StringType,    nullable = true),
      StructField("value_date_null_check",          StringType,    nullable = true),
      StructField("instance_null_check",            StringType,    nullable = true),
      StructField("set_id_null_check",              StringType,    nullable = true),
      StructField("corr_acc_no_null_check",         StringType,    nullable = true),
      StructField("bran_code_null_check",           StringType,    nullable = true),
      StructField("agent_code_null_check",          StringType,    nullable = true),
      StructField("comments",                       StringType,    nullable = true),
      StructField("current_status",                 StringType,    nullable = true),
      StructField("run_time",                       TimestampType, nullable = true),
      StructField("run_date",                       StringType,    nullable = true),
      StructField("crtd_by",                        StringType,    nullable = true)
    ))

    // ──────────────────────────────────────────────────────────────
    // STEP 6 – WRITE RESULTS TO OUTPUT TABLE
    // append mode – keeps full history of every run
    // use run_time to get latest execution for a given business date
    // ──────────────────────────────────────────────────────────────
    val resultDF = spark.createDataFrame(
      spark.sparkContext.parallelize(results),
      schema
    )

    resultDF.write
      .mode("append")
      .insertInto(OUTPUT_TABLE)

    println(s"[$THEME DQC] Job completed. Written ${results.length} rows to $OUTPUT_TABLE")

    spark.stop()
  }
}
