# goldengate in datapipelineInc


## 配置goldengate
### [Prepare] 开启归档模式
```
sqlplus / as sysdba
ALTER DATABASE ADD SUPPLEMENTAL LOG DATA;
ALTER DATABASE FORCE LOGGING;
SHUTDOWN IMMEDIATE
STARTUP MOUNT
ALTER DATABASE ARCHIVELOG;
ALTER DATABASE OPEN;
ALTER SYSTEM SWITCH LOGFILE;
ALTER SYSTEM SET ENABLE_GOLDENGATE_REPLICATION=TRUE SCOPE=BOTH;
EXIT
```
### 启动manager1
./ggsci
```
EDIT PARAM MGR
DynamicPortList 20000-20099
PurgeOldExtracts ./dirdat/*, UseCheckPoints, MinKeepHours 2
Autostart Extract E*
AUTORESTART Extract *, WaitMinutes 1, Retries 3
```
start mgr
### 启动manager ogg-bd
```
EDIT PARAM MGR
PORT 7810
```
start mgr

## 配置Extract
./ggsci
### set up the schema logging
```
DBLOGIN USERID SYSTEM@localhost:1521/orcl PASSWORD welcome1
ADD SCHEMATRANDATA SOE ALLCOLS
```

### register the integrated Extract process
```
DBLOGIN USERID SYSTEM PASSWORD welcome1
REGISTER EXTRACT EXT1 DATABASE  CONTAINER (ORCL)  (如果不是cdb) REGISTER EXTRACT EXT1 DATABASE
```
### define and add the extract
```
ADD SCHEMATRANDATA ORCL.SOE
ADD EXTRACT EXT1, INTEGRATED TRANLOG, BEGIN NOW
ADD EXTTRAIL ./dirdat/lt EXTRACT EXT1
```
### EDIT PARAM EXT1 AND START
EDIT PARAM EXT1 
```
EXTRACT EXT1
USERID SYSTEM, PASSWORD welcome1
EXTTRAIL ./dirdat/lt
SOURCECATALOG ORCL
TABLE SOE.*;
NOCOMPRESSUPDATES
GETUPDATEBEFORES
```
START EXT1

###   EDIT PARAM EXTDP1 AND START
EDIT PARAM EXTDP1
```
EXTRACT EXTDP1
RMTHOST LOCALHOST, MGRPORT 7810
USERID SYSTEM, PASSWORD welcome1 暂时不添加
RMTTRAIL ./dirdat/rt
TABLE SOE.*;
--如果有版本问题，尝试一下解决方案
RMTTRAIL ./dirdat/rt, FORMAT RELEASE 11.2
RMTTRAIL ./dirdat/rt, FORMAT LEVEL 5    //ogg-01332
```

```
ADD EXTRACT EXTDP1 EXTTRAILSOURCE ./dirdat/lt BEGIN NOW
ADD RMTTRAIL ./dirdat/rt EXTRACT EXTDP1
```
START EXTDP1

## 配置ogg-bd
### 配置 Replica (/u01/ogg-bd/dirprm/rconf.prm)
```
REPLICAT rconf
TARGETDB LIBFILE libggjava.so SET property=dirprm/conf.props
REPORTCOUNT EVERY 1 MINUTES, RATE
GROUPTRANSOPS 1000
MAP *.*.*, TARGET *.*.*;  (cdp 和 普通数据库配置不同)
```
### 配置Handler (/u01/ogg-bd/dirprm/conf.props)
```
gg.handlerlist=confluent


#The handler properties
gg.handler.confluent.type=oracle.goldengate.kafkaconnect.KafkaConnectHandler
gg.handler.confluent.kafkaProducerConfigFile=confluent.properties
gg.handler.confluent.mode=tx
gg.handler.confluent.sourceRecordGeneratorClass=oracle.goldengate.kafkaconnect.DefaultSourceRecordGenerator


#The formatter properties
gg.handler.confluent.format=oracle.goldengate.kafkaconnect.formatter.KafkaConnectFormatter
gg.handler.confluent.format.insertOpKey=I
gg.handler.confluent.format.updateOpKey=U
gg.handler.confluent.format.deleteOpKey=D
gg.handler.confluent.format.treatAllColumnsAsStrings=false
gg.handler.confluent.format.iso8601Format=false
gg.handler.confluent.format.pkUpdateHandling=update // delete-insert


goldengate.userexit.timestamp=utc
goldengate.userexit.writers=javawriter
javawriter.stats.display=TRUE
javawriter.stats.full=TRUE


gg.log=log4j
gg.log.level=INFO


gg.report.time=30sec


#Set the classpath here
gg.classpath=dirprm/:/u01/ogg-bd/ggjava/resources/lib*:/u01/ogg-bd/confluent-lib


javawriter.bootoptions=-Xmx512m -Xms32m -Djava.class.path=.:ggjava/ggjava.jar:./dirprm
```

### 配置 Kafka Connect  (/u01/ogg-bd/dirprm/confluent.properties)
```
bootstrap.servers=localhost:9092

value.serializer=org.apache.kafka.common.serialization.ByteArraySerializer
key.serializer=org.apache.kafka.common.serialization.ByteArraySerializer

schema.registry.url=http://localhost:18081

key.converter.schema.registry.url=http://localhost:18081
value.converter.schema.registry.url=http://localhost:18081

value.converter=io.confluent.connect.avro.AvroConverter
key.converter=io.confluent.connect.avro.AvroConverter
internal.value.converter=org.apache.kafka.connect.json.JsonConverter
internal.key.converter=org.apache.kafka.connect.json.JsonConverter
```

### 启动ggsci (./ggsci)
```
ADD REPLICAT RCONF, EXTTRAIL ./dirdat/rt
START RCONF
```
### how to build
```
# mvn clean install
# mv target/confluent-lib to ogg_bd
```

## TypeMapping
> 需要做好两端之间的类型完全匹配(ogg-bd <-> dp-data-system)
> 目前数据类型全部匹配 (自己手建表),需要完全类型验证，并记录类型转换表



## Troubleshooting
* Windows 配置提示jvm.dll LOAD ERROR
> 配置JAVA PATH,LD_LIBRARY_PATH=XX\jdk1.8.0_121\jre\bin;XX\jdk1.8.0_121\jre\bin\server\jvm.dll

* 插入数据后，kafka中没有数据
> 检查ogg/dirdat 看最近更新事件，如果没有变化，证明ext1 出现异常，如果ogg-bd数据没有更新 检查extdp1是否出现问题

* 能请求到 ogg-bd Kafka Connect 的8083 端口吗？
> 暂时没有找到方案，可能是配置式不对

* After 中只有update的column，没有全部column数据
> https://docs.oracle.com/goldengate/gg121211/gg-adapter/GADAD/java_msgcap_parsing.htm#GADAD141
> 在ext1.prm 中添加 NOCOMPRESSUPDATES   GETUPDATEBEFORES

* for big data 找不到dll / so
> 创建GLOBALS 添加 ENABLEMONITORAGENT， 重启mgr
