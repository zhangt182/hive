/*
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
package org.apache.hadoop.hive.ql.parse;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.messaging.json.gzip.GzipJSONMessageEncoder;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.parse.repl.PathBuilder;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.hadoop.hive.ql.exec.repl.ReplExternalTables.FILE_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestReplicationScenariosExternalTables extends BaseReplicationAcrossInstances {

  private static final String REPLICA_EXTERNAL_BASE = "/replica_external_base";

  @BeforeClass
  public static void classLevelSetup() throws Exception {
    HashMap<String, String> overrides = new HashMap<>();
    overrides.put(MetastoreConf.ConfVars.EVENT_MESSAGE_FACTORY.getHiveName(),
        GzipJSONMessageEncoder.class.getCanonicalName());
    overrides.put(HiveConf.ConfVars.REPL_DUMP_METADATA_ONLY.varname, "false");
    overrides.put(HiveConf.ConfVars.REPL_INCLUDE_EXTERNAL_TABLES.varname, "true");
    overrides.put(HiveConf.ConfVars.HIVE_DISTCP_DOAS_USER.varname,
        UserGroupInformation.getCurrentUser().getUserName());

    internalBeforeClassSetup(overrides, TestReplicationScenarios.class);
  }

  @Test
  public void replicationWithoutExternalTables() throws Throwable {
    List<String> loadWithClause = externalTableBasePathWithClause();
    List<String> dumpWithClause = Collections.singletonList
        ("'" + HiveConf.ConfVars.REPL_INCLUDE_EXTERNAL_TABLES.varname + "'='false'");

    WarehouseInstance.Tuple tuple = primary
        .run("use " + primaryDbName)
        .run("create external table t1 (id int)")
        .run("insert into table t1 values (1)")
        .run("insert into table t1 values (2)")
        .run("create external table t2 (place string) partitioned by (country string)")
        .run("insert into table t2 partition(country='india') values ('bangalore')")
        .run("insert into table t2 partition(country='us') values ('austin')")
        .run("insert into table t2 partition(country='france') values ('paris')")
        .dump(primaryDbName, null, dumpWithClause);

    // the _external_tables_file info only should be created if external tables are to be replicated not otherwise
    assertFalse(primary.miniDFSCluster.getFileSystem()
        .exists(new Path(new Path(tuple.dumpLocation, primaryDbName.toLowerCase()), FILE_NAME)));

    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("repl status " + replicatedDbName)
        .verifyResult(tuple.lastReplicationId)
        .run("use " + replicatedDbName)
        .run("show tables like 't1'")
        .verifyFailure(new String[] { "t1" })
        .run("show tables like 't2'")
        .verifyFailure(new String[] { "t2" });

    tuple = primary.run("use " + primaryDbName)
        .run("create external table t3 (id int)")
        .run("insert into table t3 values (10)")
        .run("insert into table t3 values (20)")
        .dump(primaryDbName, tuple.lastReplicationId, dumpWithClause);

    // the _external_tables_file info only should be created if external tables are to be replicated not otherwise
    assertFalse(primary.miniDFSCluster.getFileSystem()
        .exists(new Path(tuple.dumpLocation, FILE_NAME)));

    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("show tables like 't3'")
        .verifyFailure(new String[] { "t3" });
  }

  @Test
  public void externalTableReplicationWithDefaultPaths() throws Throwable {
    //creates external tables with partitions
    WarehouseInstance.Tuple tuple = primary
        .run("use " + primaryDbName)
        .run("create external table t1 (id int)")
        .run("insert into table t1 values (1)")
        .run("insert into table t1 values (2)")
        .run("create external table t2 (place string) partitioned by (country string)")
        .run("insert into table t2 partition(country='india') values ('bangalore')")
        .run("insert into table t2 partition(country='us') values ('austin')")
        .run("insert into table t2 partition(country='france') values ('paris')")
        .dump("repl dump " + primaryDbName);

    // verify that the external table info is written correctly for bootstrap
    assertExternalFileInfo(Arrays.asList("t1", "t2"),
        new Path(new Path(tuple.dumpLocation, primaryDbName.toLowerCase()), FILE_NAME));

    List<String> withClauseOptions = externalTableBasePathWithClause();

    replica.load(replicatedDbName, tuple.dumpLocation, withClauseOptions)
        .run("use " + replicatedDbName)
        .run("show tables like 't1'")
        .verifyResult("t1")
        .run("show tables like 't2'")
        .verifyResult("t2")
        .run("repl status " + replicatedDbName)
        .verifyResult(tuple.lastReplicationId)
        .run("select country from t2 where country = 'us'")
        .verifyResult("us")
        .run("select country from t2 where country = 'france'")
        .verifyResult("france");

    assertTablePartitionLocation(primaryDbName + ".t1", replicatedDbName + ".t1");
    assertTablePartitionLocation(primaryDbName + ".t2", replicatedDbName + ".t2");

    tuple = primary.run("use " + primaryDbName)
        .run("create external table t3 (id int)")
        .run("insert into table t3 values (10)")
        .run("create external table t4 as select id from t3")
        .dump("repl dump " + primaryDbName + " from " + tuple.lastReplicationId);

    // verify that the external table info is written correctly for incremental
    assertExternalFileInfo(Arrays.asList("t1", "t2", "t3", "t4"),
        new Path(tuple.dumpLocation, FILE_NAME));

    replica.load(replicatedDbName, tuple.dumpLocation, withClauseOptions)
        .run("use " + replicatedDbName)
        .run("show tables like 't3'")
        .verifyResult("t3")
        .run("select id from t3")
        .verifyResult("10")
        .run("select id from t4")
        .verifyResult("10");

    assertTablePartitionLocation(primaryDbName + ".t3", replicatedDbName + ".t3");

    tuple = primary.run("use " + primaryDbName)
        .run("drop table t1")
        .dump("repl dump " + primaryDbName + " from " + tuple.lastReplicationId);

    // verify that the external table info is written correctly for incremental
    assertExternalFileInfo(Arrays.asList("t2", "t3", "t4"),
        new Path(tuple.dumpLocation, FILE_NAME));
  }

  /**
   * @param sourceTableName  -- Provide the fully qualified table name
   * @param replicaTableName -- Provide the fully qualified table name
   */
  private void assertTablePartitionLocation(String sourceTableName, String replicaTableName)
      throws HiveException {
    Hive hiveForPrimary = Hive.get(primary.hiveConf);
    Table sourceTable = hiveForPrimary.getTable(sourceTableName);
    Path sourceLocation = sourceTable.getDataLocation();
    Hive hiveForReplica = Hive.get(replica.hiveConf);
    Table replicaTable = hiveForReplica.getTable(replicaTableName);
    Path dataLocation = replicaTable.getDataLocation();
    assertEquals(REPLICA_EXTERNAL_BASE + sourceLocation.toUri().getPath(),
        dataLocation.toUri().getPath());
    if (sourceTable.isPartitioned()) {
      Set<Partition> sourcePartitions = hiveForPrimary.getAllPartitionsOf(sourceTable);
      Set<Partition> replicaPartitions = hiveForReplica.getAllPartitionsOf(replicaTable);
      assertEquals(sourcePartitions.size(), replicaPartitions.size());
      List<String> expectedPaths =
          sourcePartitions.stream()
              .map(p -> REPLICA_EXTERNAL_BASE + p.getDataLocation().toUri().getPath())
              .collect(Collectors.toList());
      List<String> actualPaths =
          replicaPartitions.stream()
              .map(p -> p.getDataLocation().toUri().getPath())
              .collect(Collectors.toList());
      assertTrue(expectedPaths.containsAll(actualPaths));
    }
  }

  @Test
  public void externalTableReplicationWithCustomPaths() throws Throwable {
    Path externalTableLocation =
        new Path("/" + testName.getMethodName() + "/" + primaryDbName + "/" + "a/");
    DistributedFileSystem fs = primary.miniDFSCluster.getFileSystem();
    fs.mkdirs(externalTableLocation, new FsPermission("777"));

    List<String> loadWithClause = externalTableBasePathWithClause();

    WarehouseInstance.Tuple bootstrapTuple = primary.run("use " + primaryDbName)
        .run("create external table a (i int, j int) "
            + "row format delimited fields terminated by ',' "
            + "location '" + externalTableLocation.toUri() + "'")
        .dump(primaryDbName, null);

    replica.load(replicatedDbName, bootstrapTuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("show tables like 'a'")
        .verifyResults(Collections.singletonList("a"))
        .run("select * From a").verifyResults(Collections.emptyList());

    assertTablePartitionLocation(primaryDbName + ".a", replicatedDbName + ".a");

    //externally add data to location
    try (FSDataOutputStream outputStream =
        fs.create(new Path(externalTableLocation, "file1.txt"))) {
      outputStream.write("1,2\n".getBytes());
      outputStream.write("13,21\n".getBytes());
    }

    WarehouseInstance.Tuple incrementalTuple = primary.run("create table b (i int)")
        .dump(primaryDbName, bootstrapTuple.lastReplicationId);

    replica.load(replicatedDbName, incrementalTuple.dumpLocation, loadWithClause)
        .run("select i From a")
        .verifyResults(new String[] { "1", "13" })
        .run("select j from a")
        .verifyResults(new String[] { "2", "21" });

    // alter table location to something new.
    externalTableLocation =
        new Path("/" + testName.getMethodName() + "/" + primaryDbName + "/new_location/a/");
    incrementalTuple = primary.run("use " + primaryDbName)
        .run("alter table a set location '" + externalTableLocation + "'")
        .dump(primaryDbName, incrementalTuple.lastReplicationId);

    replica.load(replicatedDbName, incrementalTuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("select i From a")
        .verifyResults(Collections.emptyList());
    assertTablePartitionLocation(primaryDbName + ".a", replicatedDbName + ".a");
  }

  @Test
  public void externalTableWithPartitions() throws Throwable {
    Path externalTableLocation =
        new Path("/" + testName.getMethodName() + "/t2/");
    DistributedFileSystem fs = primary.miniDFSCluster.getFileSystem();
    fs.mkdirs(externalTableLocation, new FsPermission("777"));

    List<String> loadWithClause = externalTableBasePathWithClause();

    WarehouseInstance.Tuple tuple = primary.run("use " + primaryDbName)
        .run("create external table t2 (place string) partitioned by (country string) row format "
            + "delimited fields terminated by ',' location '" + externalTableLocation.toString()
            + "'")
        .run("insert into t2 partition(country='india') values ('bangalore')")
        .dump("repl dump " + primaryDbName);

    assertExternalFileInfo(Collections.singletonList("t2"),
        new Path(new Path(tuple.dumpLocation, primaryDbName.toLowerCase()), FILE_NAME));

    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("show tables like 't2'")
        .verifyResults(new String[] { "t2" })
        .run("select place from t2")
        .verifyResults(new String[] { "bangalore" });

    assertTablePartitionLocation(primaryDbName + ".t2", replicatedDbName + ".t2");

    // add new  data externally, to a partition, but under the table level top directory
    Path partitionDir = new Path(externalTableLocation, "country=india");
    try (FSDataOutputStream outputStream = fs.create(new Path(partitionDir, "file.txt"))) {
      outputStream.write("pune\n".getBytes());
      outputStream.write("mumbai\n".getBytes());
    }

    tuple = primary.run("use " + primaryDbName)
        .run("insert into t2 partition(country='australia') values ('sydney')")
        .dump(primaryDbName, tuple.lastReplicationId);

    assertExternalFileInfo(Collections.singletonList("t2"),
        new Path(tuple.dumpLocation, FILE_NAME));

    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("select distinct(country) from t2")
        .verifyResults(new String[] { "india", "australia" })
        .run("select place from t2 where country='india'")
        .verifyResults(new String[] { "bangalore", "pune", "mumbai" })
        .run("select place from t2 where country='australia'")
        .verifyResults(new String[] { "sydney" });

    Path customPartitionLocation =
        new Path("/" + testName.getMethodName() + "/partition_data/t2/country=france");
    fs.mkdirs(externalTableLocation, new FsPermission("777"));

    // add new partitions to the table, at an external location than the table level directory
    try (FSDataOutputStream outputStream = fs
        .create(new Path(customPartitionLocation, "file.txt"))) {
      outputStream.write("paris".getBytes());
    }

    tuple = primary.run("use " + primaryDbName)
        .run("ALTER TABLE t2 ADD PARTITION (country='france') LOCATION '" + customPartitionLocation
            .toString() + "'")
        .dump(primaryDbName, tuple.lastReplicationId);

    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("select place from t2 where country='france'")
        .verifyResults(new String[] { "paris" });

    // change the location of the partition via alter command
    String tmpLocation = "/tmp/" + System.nanoTime();
    primary.miniDFSCluster.getFileSystem().mkdirs(new Path(tmpLocation), new FsPermission("777"));

    tuple = primary.run("use " + primaryDbName)
        .run("alter table t2 partition (country='france') set location '" + tmpLocation + "'")
        .dump(primaryDbName, tuple.lastReplicationId);

    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("select place from t2 where country='france'")
        .verifyResults(new String[] {});
  }

  @Test
  public void externalTableIncrementalReplication() throws Throwable {
    WarehouseInstance.Tuple tuple = primary.dump("repl dump " + primaryDbName);
    replica.load(replicatedDbName, tuple.dumpLocation);

    tuple = primary.run("use " + primaryDbName)
        .run("create external table t1 (place string) partitioned by (country string)")
        .run("alter table t1 add partition(country='india')")
        .run("alter table t1 add partition(country='us')")
        .dump(primaryDbName, tuple.lastReplicationId);

    List<String> loadWithClause = externalTableBasePathWithClause();
    replica.load(replicatedDbName, tuple.dumpLocation, loadWithClause)
        .run("use " + replicatedDbName)
        .run("show tables like 't1'")
        .verifyResult("t1")
        .run("show partitions t1")
        .verifyResults(new String[] { "country=india", "country=us" });

    Hive hive = Hive.get(replica.getConf());
    Set<Partition> partitions =
        hive.getAllPartitionsOf(hive.getTable(replicatedDbName + ".t1"));
    List<String> paths = partitions.stream().map(p -> p.getDataLocation().toUri().getPath())
        .collect(Collectors.toList());

    tuple = primary
        .run("alter table t1 drop partition (country='india')")
        .run("alter table t1 drop partition (country='us')")
        .dump(primaryDbName, tuple.lastReplicationId);

    replica.load(replicatedDbName, tuple.dumpLocation)
        .run("select * From t1")
        .verifyResults(new String[] {});

    for (String path : paths) {
      assertTrue(replica.miniDFSCluster.getFileSystem().exists(new Path(path)));
    }

  }

  private List<String> externalTableBasePathWithClause() throws IOException, SemanticException {
    Path externalTableLocation = new Path(REPLICA_EXTERNAL_BASE);
    DistributedFileSystem fileSystem = replica.miniDFSCluster.getFileSystem();
    externalTableLocation = PathBuilder.fullyQualifiedHDFSUri(externalTableLocation, fileSystem);
    fileSystem.mkdirs(externalTableLocation);

    // this is required since the same filesystem is used in both source and target
    return Collections.singletonList(
        "'" + HiveConf.ConfVars.REPL_EXTERNAL_TABLE_BASE_DIR.varname + "'='"
            + externalTableLocation.toString() + "'"
    );
  }

  private void assertExternalFileInfo(List<String> expected, Path externalTableInfoFile)
      throws IOException {
    DistributedFileSystem fileSystem = primary.miniDFSCluster.getFileSystem();
    assertTrue(fileSystem.exists(externalTableInfoFile));
    InputStream inputStream = fileSystem.open(externalTableInfoFile);
    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
    Set<String> tableNames = new HashSet<>();
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      String[] components = line.split(",");
      assertEquals("The file should have tableName,base64encoded(data_location)",
          2, components.length);
      tableNames.add(components[0]);
      assertTrue(components[1].length() > 0);
    }
    assertTrue(expected.containsAll(tableNames));
    reader.close();
  }
}
