/**
 * <PRE>
 *  <B>Description</B>
 *   Connects to vSphere and collect performance metrics from all the known Hosts and Guests
 *
 *  <B>Other stuff</B>
 *   Author:      Sebastian YEPES F. (mailto:syepes@gmail.com)
 *   Copyright:   Copyright (c) 2012 Sebastian YEPES F.
 *   License:     BSD
 *
 *  <B>Warranty</B>
 *   This software is provided "as is" and without any express or implied warranties, including, without limitation, i am not held responsible for any _damage_ or _loss_ _of_ _data_ produced by this software
 *
 * @author Sebastian YEPES FERNANDEZ (syepes@gmail.com)
 */


package com.allthingsmonitoring

import org.slf4j.*
import groovy.util.logging.Slf4j
import ch.qos.logback.classic.*
import static ch.qos.logback.classic.Level.*
import org.codehaus.groovy.runtime.StackTraceUtils
import groovyx.gpars.GParsPool
import groovyx.gpars.util.PoolUtils

import com.xlson.groovycsv.CsvParser
import java.util.regex.Matcher
import java.util.zip.*
import java.util.jar.Manifest
import java.util.jar.Attributes

import groovy.time.*
import java.text.SimpleDateFormat

import java.net.URL
import com.vmware.vim25.*
import com.vmware.vim25.mo.*

import java.rmi.RemoteException
import java.net.MalformedURLException

import java.security.MessageDigest
import javax.crypto.*
import javax.crypto.spec.*



@Slf4j
class vSphere2Graphite {
  TimeDuration lastExecTime = new TimeDuration(0, 0, 0, 0)
  TimeDuration startFromExecTime = new TimeDuration(0, 0, 0, 0)

  /** Configuration file location */
  final String CFG_FILE  = 'config.groovy'
  ConfigObject cfg


  /**
   * Constructor
   */
  vSphere2Graphite() {
    cfg = readConfigFile()
    Attributes manifest = getManifestInfo()
    log.info "Initialization: Class: ${this.class.name} / Collecting samples: ${cfg?.vcs?.perf_max_samples} = ${cfg?.vcs?.perf_max_samples * 20}sec / Version: ${manifest?.getValue('Specification-Version')} / Built-Date: ${manifest?.getValue('Built-Date')}"
  }


  /**
   * Load configuration settings
   *
   * @return ConfigObject with the configuration elements
   */
  ConfigObject readConfigFile() {
    try {
      ConfigObject cfg = new ConfigSlurper().parse(new File(CFG_FILE).toURL())
      if (cfg) {
        log.trace "The configuration files: ${CFG_FILE} was read correctly"
        return cfg
      } else {
        log.error "Verify the content of the configuration file: ${CFG_FILE}"
        throw new RuntimeException("Verify the content of the configuration file: ${CFG_FILE}")
      }
    } catch(FileNotFoundException e) {
      log.error "The configuration file: ${CFG_FILE} was not found"
      throw new RuntimeException("The configuration file: ${CFG_FILE} was not found")
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Configuration file exception: ${getStackTrace(e)}"
      throw new RuntimeException("Configuration file exception:\n${getStackTrace(e)}")
    }
  }

  /**
   * Retrieves the Manifest Info from the JAR file
   *
   * @return JAR MainAttributes
   */
  Attributes getManifestInfo() {
    Class clazz = this.getClass()
    String className = clazz.getSimpleName() + ".class"
    String classPath = clazz.getResource(className).toString()
    // Class not from JAR
    if (!classPath.startsWith("jar")) { return null }

    String manifestPath = classPath.substring(0, classPath.lastIndexOf('!') + 1) + "/META-INF/MANIFEST.MF"
    Manifest manifest
    try {
      manifest = new Manifest(new URL(manifestPath).openStream())
    } catch(Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.warn "Manifest: ${getStackTrace(e)}"
    }

    return manifest.getMainAttributes()
  }

  // Gets the StackTrace and returns a string
  String getStackTrace(Throwable t) {
    StringWriter sw = new StringWriter()
    PrintWriter pw = new PrintWriter(sw, true)
    t.printStackTrace(pw)
    pw.flush()
    sw.flush()
    return sw.toString()
  }


  /**
   * Encrypt string
   *
   * @param plaintext String that should be encrypted
   * @return Encrypted String
   */
  /*
  private String encrypt(String plaintext) {
    String salt = java.net.InetAddress.getLocalHost().getHostName()
    String key = 'iFdZMpygE-0'
    Cipher c = Cipher.getInstance('AES')
    byte[] keyBytes = MessageDigest.getInstance('SHA-1').digest("${salt}${key}".getBytes())[0..<16]
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, 'AES'))
    return c.doFinal(plaintext.bytes).encodeBase64() as String
  }*/

  /**
   * Decrypt string
   *
   * @param ciphertext Encrypted String that should be decrypted
   * @return Decrypted String
   */
  private String decrypt(String ciphertext) {
    String salt = java.net.InetAddress.getLocalHost().getHostName()
    String key = 'iFdZMpygE-0'
    Cipher c = Cipher.getInstance('AES')
    byte[] keyBytes = MessageDigest.getInstance('SHA-1').digest("${salt}${key}".getBytes())[0..<16]
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, 'AES'))
    return new String(c.doFinal(ciphertext.decodeBase64()))
  }


  /**
   * Connectes to the vSphere server
   *
   * @param vcs URL of the vSphere server
   * @return ServiceInstance
   */
  ServiceInstance vSphereConnect(String vcs) {
    Date timeStart = new Date()
    ServiceInstance si

    try {
      si = new ServiceInstance(new URL(vcs), cfg.vcs.user, decrypt(cfg.vcs.pwd), true)
      Date timeEnd = new Date()
      log.info "Connected to vSphere (${vcs}) in ${TimeCategory.minus(timeEnd,timeStart)}"
    } catch (InvalidLogin e) {
      log.error "Invalid login vSphere: ${cfg.vcs.user}"
    } catch (RemoteException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Remote exception: ${getStackTrace(e)}"
    } catch (MalformedURLException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "MalformedURLexception: ${getStackTrace(e)}"
    }

    return si
  }

  /**
   * Disconnect from the vSphere server
   *
   * @param si ServiceInstance
   */
  void vSphereDisconnect(ServiceInstance si) {
    Date timeStart = new Date()

    try {
      si.getServerConnection().logout()
    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "vSphereDisconnect: ${getStackTrace(e)}"
    }
    Date timeEnd = new Date()
    log.info "Disconected to vSphere in ${TimeCategory.minus(timeEnd,timeStart)}"
  }

  /**
   * PerformanceManager
   *
   * @param si ServiceInstance
   * @return PerformanceManager
   */
  PerformanceManager getPerformanceManager(ServiceInstance si) {
    PerformanceManager perfMgr

    try {
      perfMgr = si?.getPerformanceManager()
    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "getPerformanceManager: ${getStackTrace(e)}"
    }
    return perfMgr
  }

  /**
   * Get a specific VM
   *
   * @param si ServiceInstance
   * @param name Name of the VM
   * @return InventoryNavigator
   */
  ManagedEntity getVM(ServiceInstance si, String name) {
    ManagedEntity vm
    Folder rootFolder = si.getRootFolder()
    log.info "Getting VM: ${name} from: ${rootFolder.getName()}"

    try {
      vm = new InventoryNavigator(rootFolder).searchManagedEntity("VirtualMachine", name)
      if (vm){
        log.info "Found ${name} Virtual Machine"
      } else {
        log.error "Not Found ${name} Virtual Machine"
      }

    } catch (RemoteException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "getVM: ${getStackTrace(e)}"
    }

    return vm
  }

  /**
   * Get all the VM's know by the vSphere server
   *
   * @param si ServiceInstance
   * @return InventoryNavigator
   */
  ManagedEntity[] getVMs(ServiceInstance si) {
    ManagedEntity[] vms
    Folder rootFolder = si.getRootFolder()
    log.info "Getting VMs from: ${rootFolder.getName()}"

    try {
      vms = new InventoryNavigator(rootFolder).searchManagedEntities("VirtualMachine")
      if (vms){
        log.info "Found ${vms.size()} Virtual Machines"
      } else {
        log.error "Not Found ${name} Virtual Machines"
      }

    } catch (RemoteException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "getVMs: ${getStackTrace(e)}"
    }

    return vms
  }

  /**
   * Get all the EXSi host know by the vSphere server
   *
   * @param si ServiceInstance
   * @return InventoryNavigator
   */
  ManagedEntity[] getHosts(ServiceInstance si) {
    //InventoryNavigator[] hosts
    ManagedEntity[] hosts
    Folder rootFolder = si.getRootFolder()
    log.info "Getting ESXi Hosts from: ${rootFolder.getName()}"

    try {
      hosts = new InventoryNavigator(rootFolder).searchManagedEntities("HostSystem")
      if (hosts) {
        log.info "Found ${hosts.size()} Host Systems"
      } else {
        log.error "Not Found Host Systems"
      }

    } catch (RemoteException e) {
      StackTraceUtils.deepSanitize(e)
      log.error "getHosts: ${getStackTrace(e)}"
    }

    return hosts
  }

  /**
   * Get performace counters and returns a Data strunture containing all there info
   *
   * @param perfMgr A reference to the PerformanceManager used to make the method call.
   * @return LinkedHashMap with all the known performance counters
   */
  LinkedHashMap getPerformanceCounters(PerformanceManager perfMgr) {
    LinkedHashMap perfMetrics = [:]
    PerfCounterInfo[] perfCounters = perfMgr?.getPerfCounter()

    perfCounters.each {
      LinkedHashMap metric = [MetricId:it.getKey(),
                              Metric:"${it.getGroupInfo().getKey()}.${it.getNameInfo().getKey()}_${it.getRollupType()}-${it.getUnitInfo().getKey()}",
                              RollupType:it.getRollupType(),
                              Level:it.getLevel(),
                              PerDeviceLevel:it.getPerDeviceLevel(),
                              StatsType:it.getStatsType(),
                              UnitInfo:it.getUnitInfo().getKey(),
                              NameInfo:it.getNameInfo().getKey(),
                              GroupInfo:it.getGroupInfo().getKey()]

      perfMetrics[it.getKey()] = metric
    }
    return perfMetrics
  }

  /**
   * Create the performance query filter
   *
   * @param me The ManagedObject managed object whose performance statistics are being queried
   * @param metricIds The performance metrics to be retrieved
   * @param maxSample The maximum number of samples to be returned from server
   * @param perfInterval The interval (samplingPeriod) in seconds for which performance statistics are queried
   * @return PerfQuerySpec
   */
  PerfQuerySpec createPerfQuerySpec(ManagedEntity me, PerfMetricId[] metricIds, int maxSample, int perfInterval) {
    PerfQuerySpec qSpec = new PerfQuerySpec()
    qSpec.setEntity(me.getMOR())
    //qSpec.setEntity(me.getRuntime().getHost())

    // set the maximum of metrics to be return only appropriate in real-time performance collecting

    if (startFromExecTime.toMilliseconds()){
      // Retrieve the numbers of samples passed by the parameter 'sf'
      qSpec.setMaxSample(Math.round((startFromExecTime.toMilliseconds()/1000)/20).toInteger())
    } else {
      // Take into account the execution time and get the extra samples.
      int execDelaySamples = Math.round((lastExecTime.toMilliseconds()/1000)/20).plus(3)
      qSpec.setMaxSample(maxSample + execDelaySamples)
    }


    qSpec.setMetricId(metricIds)

    //qSpec.setFormat("normal")
    qSpec.setFormat("csv")

    // set the interval to the refresh rate for the entity
    qSpec.setIntervalId(perfInterval)

    return qSpec
  }

  /**
   * Collect the performance metrics
   *
   * @param perfMgr A reference to the PerformanceManager used to make the method call
   * @param maxSample The maximum number of samples to be returned from server
   * @param vm The ManagedObject managed object whose performance statistics are being queried
   * @return PerfEntityMetricBase The metric values for the specified entity or entities.
   */
  PerfEntityMetricBase[] getPerfMetrics(PerformanceManager perfMgr,int maxSample,ManagedEntity vm) {

    PerfProviderSummary pps = perfMgr.queryPerfProviderSummary(vm)
    int refreshRate = pps.getRefreshRate().intValue()
    log.trace "Collecting Performance Metrics RefreshRate: ${refreshRate}"

    PerfMetricId[] pmis = perfMgr.queryAvailablePerfMetric(vm, null, null, refreshRate)
    // For the instance property, specify an asterisk (*) to retrieve instance and aggregate data or a zero-length string ("") to retrieve aggregate data only
    pmis.each { it.setInstance("*") }

    PerfQuerySpec qSpec = createPerfQuerySpec(vm, pmis, maxSample, refreshRate)

    // Use QueryPerf to obtain metrics for multiple entities in a single call.
    // Use QueryPerfComposite to obtain statistics for a single entity with its descendent objects statistics for a host and all its virtual machines, for example.
    PerfEntityMetricBase[] pValues = perfMgr.queryPerf(qSpec)

    return pValues
  }

  /**
   * Generate a Data structure of the collected metrics: DS[MNAME] = [ts:v,ts:v]
   *
   * @param pValues The metric values for the specified entity or entities
   * @param perfMetrics Performance Counters HashMap
   * @param hi Host information HashMap
   * @return LinkedHashMap DS[MNAME] = [ts:v,ts:v]
   */
  LinkedHashMap getValues(pValues,LinkedHashMap perfMetrics,LinkedHashMap hi) {
    LinkedHashMap metricData = [:]

    for (pValue in pValues) {

      if (pValue instanceof PerfEntityMetric) {
        log.debug "Processing PerfEntityMetric: ${pValue.getEntity().getType()} (${pValue.getEntity().get_value()})"

      } else if (pValue instanceof PerfEntityMetricCSV) {
        log.trace "Processing +PerfEntityMetricCSV: ${pValue.getEntity().getType()} (${pValue.getEntity().get_value()})"

        //def A =  ['One', 'Two', 'Three', 'Four', 'Five']
        //def B =  ['1', '2', '3', '4', '5']
        //[A, B].transpose().inject([:]) { a, b -> a[b[0]] = b[1]; a }
        // Result: [One:1, Two:2, Three:3, Four:4, Five:5]

        // Get the odd (refreshRate) and even (Time Stamp) elements out of a list
        def (rRate,tStamp) = pValue.getSampleInfoCSV().tokenize(',').collate( 2 ).transpose()

        // Convert incoming time to the Graphite TZ and to the epoch format
        tStamp = tStamp.collect { ((convertTimeZone(it,cfg.vcs.timezone,cfg.graphite.timezone)).time.toString().toLong()/1000).toInteger() }

        // Create data structure metricData[Metric][[Timestamp:Value]] for all the instances
        // [net.usage_average-kiloBytesPerSecond:[1339152760000:0, 1339152780000:1, 1339152800000:0, 1339152820000:0, 1339152840000:0, 1339152860000:0]
        pValue.getValue().each {

          String instID = it?.getId()?.getInstance()?.toString()
          String instName

          // Organize the instance name depending on the metrics
          if (!instID ) {
            instName = 'avg'
          } else if (perfMetrics[it.getId()?.getCounterId()]['Metric'] ==~ /^datastore.*/) {
            if (hi['datastore'][instID]?.containsKey('type')) {
              instName = "${hi['datastore'][instID]['type']}.${hi['datastore'][instID]['name']}"
            } else {
              log.warn "The datastore Instance: ${instID} has no type"
            }

          } else if (perfMetrics[it.getId()?.getCounterId()]['Metric'] ==~ /^disk.*/) {
            if (hi['disk'][instID]?.containsKey('type')) {
              instName = "${hi['disk'][instID]['type']}.${hi['disk'][instID]['vendor']}-${instID[-4..-1]}"
            } else {
              log.warn "The disk Instance: ${instID} has no type"
            }

          } else if (perfMetrics[it.getId()?.getCounterId()]['Metric'] ==~ /^storagePath.*/) {
            if (hi['storagePath'][instID]?.containsKey('pathname')){
              instName = "${hi['storagePath'][instID]['pathname']}"
            } else {
              log.warn "The storagePath Instance: ${instID} has no pathname"
            }
          } else if (perfMetrics[it.getId()?.getCounterId()]['Metric'] ==~ /^sys.*/) {
            if (instID == '/') {
              instName = 'root'
            } else {
              instName = instID.replaceAll(~/\//, '.').replaceAll(~/[_]/, '').trim()
            }
          } else {
            instName = instID ?: 'FIXME'
          }

          // TODO: Try to optimize this
          //def mpath = "${perfMetrics[it.getId().getCounterId()]['Metric']}.${instName}".replaceAll(~/[:]/, '-')
          //mpath = mpath.replaceAll(/(\w+).(.*)/,/$1.$instName.$2/).replaceAll(~/[:]/, '-')

          // Put the metric instance in the middle (metric-type.instance.metric)
          String mpath
          Matcher m
          if ((m = perfMetrics[it.getId().getCounterId()]['Metric'] =~ /(\w+).(.*)/)) {
            mpath = "${m[0][1]}.${instName}.${m[0][2]}".replaceAll(~/[:]/, '-')
          } else {
            mpath = "FIXME.${perfMetrics[it.getId().getCounterId()]['Metric']}"
          }

          metricData[mpath] = [tStamp,it.getValue().split(',')].transpose().inject([:]) { a, b -> a[b[0]] = b[1]; a }
        }

      } else { log.error "UnExpected sub-type of PerfEntityMetricBase: ${pValue.class}" }
    }

    return metricData
  }



  /**
   * Collect the metrics for all the VMs
   *
   * @param si ServiceInstance
   * @param perfMgr A reference to the PerformanceManager used to make the method call
   * @param perfMetrics Performance Counters HashMap
   * @param hi Host information HashMap
   * @param maxSample The maximum number of samples to be returned from server
   * @param vms The interval (samplingPeriod) in seconds for which performance statistics are queried
   * @param metricsData Referenca to the shared variable
   */
  void getGuestMetrics(ServiceInstance si,PerformanceManager perfMgr,LinkedHashMap perfMetrics,LinkedHashMap hi,int maxSample,ManagedEntity[] vms,LinkedHashMap metricsData) {

    vms.each { vm ->
      // Can not collect metrics if VM is not Running
      if (vm?.getSummary()?.getRuntime()?.getPowerState()?.toString() != 'poweredOn') { return }
      PerfEntityMetricBase[] pValues
      String vmName,esxHost

      try {
        vmName = vm.getSummary().getConfig().getName().split('\\.')[0].replaceAll(~/[\s-\.]/, "-").toLowerCase()
        esxHost = new HostSystem(si.getServerConnection(), vm.getRuntime().getHost()).getSummary().getConfig().getName()
        esxHost = esxHost.split('\\.')[0].replaceAll(~/[\s-\.]/, "-").toLowerCase() // Get only the hostname of the FQDN

        pValues = getPerfMetrics(perfMgr,maxSample,vm)
      } catch (Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.error "getGuestMetrics: ${getStackTrace(e)}"
      }

      if (vmName && esxHost && pValues) {
        metricsData[(vmName)] = [type:'Guest', Host:esxHost, Metrics:getValues(pValues, perfMetrics, hi)]
      } else {
        log.warn "Could not retrieve metrics for the VM: ${vmName} (${esxHost})"
      }
    }
  }


  /**
   * Collect the metrics for all the Hosts
   *
   * @param perfMgr A reference to the PerformanceManager used to make the method call
   * @param perfMetrics Performance Counters HashMap
   * @param hi Host information HashMap
   * @param maxSample The maximum number of samples to be returned from server
   * @param hosts The interval (samplingPeriod) in seconds for which performance statistics are queried
   * @param metricsData Referenca to the shared variable
   */
  void getHostsMetrics(PerformanceManager perfMgr,LinkedHashMap perfMetrics,LinkedHashMap hi,int maxSample,ManagedEntity[] hosts,LinkedHashMap metricsData) {

    hosts.each { host ->
      // Can not collect metrics if Host is not Running
      if (host?.getSummary()?.getRuntime()?.getPowerState()?.toString() != 'poweredOn') { return }
      PerfEntityMetricBase[] pValues
      String esxHost

      try {
        esxHost = host.getSummary().getConfig().getName().split('\\.')[0].replaceAll(~/[\s-\.]/, "-").toLowerCase()

        pValues = getPerfMetrics(perfMgr,maxSample,host)
      } catch (Exception e) {
        StackTraceUtils.deepSanitize(e)
        log.error "getHostsMetrics: ${getStackTrace(e)}"
      }


      if (esxHost && pValues) {
        metricsData[(esxHost)] = [type:'Host', Host:esxHost, Metrics:getValues(pValues, perfMetrics, hi)]
      } else {
        log.warn "Could not retrieve metrics for the Host: ${esxHost}"
      }
    }
  }

  /**
   * Collects datastore, disk and storagePath information
   *
   * @param hosts The ManagedObject managed object whose performance statistics are being queried
   * @return LinkedHashMap Host information HashMap
   */
  LinkedHashMap getHostInfo(ManagedEntity[] hosts) {
    LinkedHashMap hostInfo = [:]
    LinkedHashMap dsInfo = [:]
    LinkedHashMap diskInfo = [:]
    LinkedHashMap pathInfo = [:]

    hosts.each { host ->
      String hostName = host.getSummary().getConfig().getName().split('\\.')[0].replaceAll(~/[\s-\.]/, "-").toLowerCase()
      // Get datastore info
      HostStorageSystem hds = host.getHostStorageSystem() // HostStorageSystem
      HostFileSystemVolumeInfo vi = hds.getFileSystemVolumeInfo() // HostFileSystemVolumeInfo
      HostFileSystemMountInfo[] mis = vi.getMountInfo() // HostFileSystemMountInfo
      mis.each {
        HostFileSystemVolume hfsv = it.getVolume() // HostFileSystemVolume
        dsInfo[hfsv.getUuid()] = [name:hfsv.getName().replaceAll(~/[()]/, '').replaceAll(~/[\s-\.]/, "-"),type:hfsv.getType().trim(), host:hostName]
      }

      // Get disk info
      HostStorageDeviceInfo hsdi = hds.getStorageDeviceInfo() // HostStorageDeviceInfo
      ScsiLun[] sls = hsdi.getScsiLun()
      sls.each { diskInfo[it.getCanonicalName()] = [type:it.getLunType().trim(), vendor:it.getVendor().trim(), uuid:it.getUuid(), host:hostName] }

      // Get Multipath info
      HostMultipathInfo hmi = hsdi.getMultipathInfo() // HostMultipathInfo
      hmi.getLun().each { // HostMultipathInfoLogicalUnit
        HostMultipathInfoPath[] hmips = it.getPath() // HostMultipathInfoPath
        hmips.each { p ->
          pathInfo[p.getName()] = [id:it.getId(), adapter:p.getAdapter(), lun:p.getLun(), name:p.getName()]
        }
      }

      // Link paths with disks
      pathInfo.each { p ->
        diskInfo.each { d ->
          if (p.value['id'] == d.value['uuid']) {
           if (d.value['type'] == 'cdrom') {
             pathInfo[p.key].pathname = "${p.value['adapter'].replaceAll('key-vim.host.', '')}-${d.value['type']}-${d.value['vendor']}"
           } else {
             pathInfo[p.key].pathname = "${p.value['adapter'].replaceAll('key-vim.host.', '')}-${d.value['type']}-${d.value['vendor']}-${p.key[-4..-1]}"
           }
          }
        }
      }

    }

    hostInfo['datastore'] = dsInfo
    hostInfo['disk'] = diskInfo
    hostInfo['storagePath'] = pathInfo
    return hostInfo
  }


  // Dor debugging
  void displayValues(pValues, LinkedHashMap perfMetrics) {
    for (pValue in pValues) {
      println "Entity: ${pValue.getEntity().getType()} : ${pValue.getEntity().get_value()}"
      println "Entity: ${pValue.getEntity().class.methods.name.sort()}"

      if (pValue instanceof PerfEntityMetric) {
        println "+PerfEntityMetric"

      } else if (pValue instanceof PerfEntityMetricCSV) {
        println "+PerfEntityMetricCSV"
        println "pValues Interval: ${pValue.getSampleInfoCSV()}"

        // Get the odd (refreshRate) and even (Time Stamp) elements out of a list
        //ts.tokenize(',').eachWithIndex { item, idx -> println "${idx} : ${item} : ${( idx % 2 ? 'odd' : 'even')}" }
        def (rRate,tStamp) = pValue.getSampleInfoCSV().tokenize(',').collate( 2 ).transpose()
        tStamp.each {
          println new Date().parse("yyyy-MM-dd'T'HH:mm:ss'Z'", it)
        }

        pValue.getValue().each {
          println "\tPerfCounterId / Name: ${it.getId().getCounterId()} / ${perfMetrics[it.getId().getCounterId()]}"
          println "\tCSV sample values:" + it.getValue()
        }
      } else { println "UnExpected sub-type of PerfEntityMetricBase: ${pValue.class}" }
    }
  }


  /**
   * Sends metrics to the Graphite server
   *
   * @param data Metrics Data structure
   */
  void sendMetrics2Graphite(LinkedHashMap data) {
    Date timeStart = new Date()
    String nodeIdentifier = java.net.InetAddress.getLocalHost().getHostName()
    int sentCount = 0
    int progessCount = 0
    Socket socket
    log.info "Sending Metrics to Graphite (${cfg.graphite.host}:${cfg.graphite.port})"

    try {
      socket = new Socket(cfg.graphite.host, cfg.graphite.port)

      data.each { node ->
        node.each { hash  ->
          hash.value['Metrics'].each { metric ->
            metric.value.each { ts ->
              log.trace "Type:${hash.value['type']} / Host:${hash.value['Host']} / VM:${node.key} / Metric:${metric.key} / Val:${ts.value} / TS:${ts.key}"

              String mpath
              if (hash.value['type'] == 'Host') {
                mpath = "${cfg.graphite.prefix}.${hash.value['Host']}.${hash.value['type']}.${metric.key}"
              } else {
                mpath = "${cfg.graphite.prefix}.${hash.value['Host']}.${hash.value['type']}.${node.key}.${metric.key}"
              }

              BigDecimal mvalue = (ts.value.toString().isEmpty()) ? 0 : ts.value.toBigDecimal()
              int mtimes = ts.key
              StringBuilder msg = new StringBuilder()
              msg << "${mpath} ${mvalue} ${mtimes}"

              // Only send metrics if they are different than 0
              if (mvalue) {
                if (progessCount >= 10000) {
                  log.debug "Sending ${hash.value['type']} Metrics to Graphite (${cfg?.graphite?.host}:${cfg?.graphite?.port}) using 'TCP' from (${nodeIdentifier}): ${msg} (${new Date(ts.key.toLong()).format("hh:mm:ss - dd/MM/yyyy")})"
                  progessCount = 0
                }

                //msg <<= '\n'
                msg << '\n'
                Writer writer = new OutputStreamWriter(socket.getOutputStream())
                writer.write(msg.toString())
                writer.flush()

                progessCount++
                sentCount++
              }
            }
          }
        }
      }
    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "Socket exception: ${getStackTrace(e)}"
    } finally {
      socket?.close()
    }
    Date timeEnd = new Date()
    log.info "Finished sending Metrics ${sentCount} to Graphite in ${TimeCategory.minus(timeEnd,timeStart)}"
  }


  /////////////////////////////////////
  //  Events
  ////////////////
  void displayEvent(e) {
     log.info "Type: ${e.getClass().getName()}"
     log.info "Key: ${e.getKey()}"
     log.info "ChainId: ${e.getChainId()}"
     log.info "User: ${e.getUserName()}"
     log.info "Time: ${e.getCreatedTime().getTime()}"
     log.info "FormattedMessage: ${e.getFullFormattedMessage()}"
     log.info "Datacenter: ${e.getDatacenter()}"
     log.info "ComputeResource: ${e?.getComputeResource()?.getComputeResource()}"
     log.info "Host: ${e?.getHost()?.getHost()}"
     log.info "VM: ${e?.getVm()?.getVm()}"
   }

  // def em = si.getEventManager()
  def getEvants(ServiceInstance si,em) {
    // create a filter spec for querying events
    def efs = new EventFilterSpec()

    // limit to the following events
    def eventFilterList = ['VmFailedToPowerOnEvent','HostConnectionLostEvent','VmPoweredOffEvent','VmPoweredOnEvent','VmMigratedEvent','InsufficientFailoverResourcesEv ent'] as String[]
    efs.setType(eventFilterList)

    // limit to error and warning only
    //def severityfilterList = ['error', 'warning'] as String[]
    //efs.setCategory(severityfilterList)

    // limit to the children of root folder
    def eFilter = new EventFilterSpecByEntity()
    eFilter.setEntity(si.getRootFolder().getMOR())
    eFilter.setRecursion(EventFilterSpecRecursionOption.children)

    // limit to the events happened since a month ago
    def tFilter = new EventFilterSpecByTime()
    Calendar startTime = si.currentTime()
    //startTime.roll(Calendar.MONTH, false)
    //startTime.roll(Calendar.HOUR, false)
    startTime.roll(Calendar.MONDAY, false)
    tFilter.setBeginTime(startTime)
    efs.setTime(tFilter)

    // limit to the user of "administrator"
    //def uFilter = new EventFilterSpecByUsername()
    //uFilter.setSystemUser(false)
    //def userFilterList = ['administrator'] as String[]
    //uFilter.setUserList(userFilterList)

    def events = em.queryEvents(efs)
    log.info "Received ${events?.size()} events from vSphere"
    return events
  }


  // Converts a time stamp from one Time zone (sourceTZ) another (destTZ)
  Date convertTimeZone(String time, String sourceTZ, String destTZ) {
    final String DATE_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"
    SimpleDateFormat sdf = new SimpleDateFormat(DATE_TIME_FORMAT)
    Date specifiedTime

    try {
      if (sourceTZ != null){
        sdf.setTimeZone(TimeZone.getTimeZone(sourceTZ))
      } else {
        sdf.setTimeZone(TimeZone.getDefault()) // default to server's timezone
      }
      specifiedTime = sdf.parse(time)
    } catch (Exception e1) {
      try {
        specifiedTime = new Date().parse(DATE_TIME_FORMAT, time)
      } catch (Exception e2) {
        return time
      }
    }

    // switch timezone
    if (destTZ != null) {
      sdf.setTimeZone(TimeZone.getTimeZone(destTZ))
    } else {
      sdf.setTimeZone(TimeZone.getDefault()) // default to server's timezone
    }

    //sdf.format(specifiedTime)
    new Date().parse("yyyy-MM-dd'T'HH:mm:ss'Z'", sdf.format(specifiedTime))
  }



  /**
   * Dump available counters in vSphere
   *
   */
  void dumpCounters() {
    Date timeStart = new Date()
    log.info "Getting available Counters in vSphere"

    ArrayList vcs = cfg?.vcs?.urls
    vcs.each { vc ->
      ServiceInstance si = vSphereConnect(vc)
      if (!si) { log.error "Error establishing connection to the vSphere server: ${vc}"; return }

      // Find and create p}rformance metrics (counters) hash table
      PerformanceManager perfMgr = getPerformanceManager(si)
      LinkedHashMap perfMetrics = getPerformanceCounters(perfMgr)
      vSphereDisconnect(si)

      println "vSphere: ${vc}"
      perfMetrics.each { c ->
        println "Counter ID: ${c.key}"
        c.value.each {
         println "\t${it}"
        }
      }
    }

    Date timeEnd = new Date()
    log.info "Finished Collecting vSphere Counters in ${TimeCategory.minus(timeEnd,timeStart)}"
  }


  /**
   * Dump metrics
   *
   * 3 Samples + startfrom
   */
  void dumpMetrics() {
    Date timeStart = new Date()
    log.info "Start Collecting vSphere Metrics"

    ArrayList vcs = cfg?.vcs?.urls
    vcs.each { vc ->
      ServiceInstance si = vSphereConnect(vc)
      if (!si) { log.error "Error establishing connection to the vSphere server: ${vc}"; return }

      // Find and create p}rformance metrics (counters) hash table
      PerformanceManager perfMgr = getPerformanceManager(si)
      LinkedHashMap perfMetrics = getPerformanceCounters(perfMgr)

      ManagedEntity[] hosts = getHosts(si) // Get Hosts
      LinkedHashMap hi = getHostInfo(hosts) // Get Host info
      ManagedEntity[] guests = getVMs(si) // Get VMs

      // Collect Host and Guest performance metrics
      LinkedHashMap metricsData = [:]
      getHostsMetrics(perfMgr,perfMetrics,hi,cfg.vcs.perf_max_samples,hosts,metricsData)
      getGuestMetrics(si,perfMgr,perfMetrics,hi,cfg.vcs.perf_max_samples,guests,metricsData)

      vSphereDisconnect(si)

      // Print metrics
      println "vSphere: ${vc}"
      metricsData.each { n ->
        println "${n.key}:"
        n.value.each {
          println "\t${it.key}: ${it.value}"
        }
      }
    }

    Date timeEnd = new Date()
    log.info "Finished Collecting vSphere Metrics in ${TimeCategory.minus(timeEnd,timeStart)}"
  }




  /**
   * Collect, Process and send the VM Metrics in Parallel
   *
   * @param vcs URL list of the vSphere servers
   */
  void collectVMMetrics(ArrayList vcs) {
    Date timeStart = new Date()

    GParsPool.withPool(cfg?.vcs?.urls.size()) {
      log.info "Start Collecting vSphere Metrics in parallel using PoolSize: ${cfg?.vcs?.urls.size()}/${PoolUtils.retrieveDefaultPoolSize()} (Current/Max) / Last execution time: ${lastExecTime}"

      vcs.eachParallel { vc ->
        ServiceInstance si = vSphereConnect(vc)
        if (!si) { log.error "Error establishing connection to the vSphere server: ${vc}"; return }

        // Find and create p}rformance metrics (counters) hash table
        PerformanceManager perfMgr = getPerformanceManager(si)
        LinkedHashMap perfMetrics = getPerformanceCounters(perfMgr)

        ManagedEntity[] hosts = getHosts(si) // Get Hosts
        LinkedHashMap hi = getHostInfo(hosts) // Get Host info
        ManagedEntity[] guests = getVMs(si) // Get VMs

        // Collect Host and Guest performance metrics
        LinkedHashMap metricsData = [:]
        getHostsMetrics(perfMgr,perfMetrics,hi,cfg.vcs.perf_max_samples,hosts,metricsData)
        getGuestMetrics(si,perfMgr,perfMetrics,hi,cfg.vcs.perf_max_samples,guests,metricsData)

        vSphereDisconnect(si)

        // Send metrics
        sendMetrics2Graphite(metricsData)
      }
    }

    Date timeEnd = new Date()
    lastExecTime = TimeCategory.minus(timeEnd,timeStart)
    log.info "Finished Collecting vSphere Metrics in ${lastExecTime}"
  }

  /**
   * Run as daemon the Collecting processes
   *
   */
  void runAsDaemon() {
    try {
      while(true){
        // Collect VM Metrics
        collectVMMetrics(cfg?.vcs?.urls)

        System.gc()

        // Last 1 Minute (3x20s = 60s) / Last 2 Minute (6x20s = 120s) / Last 5 Minute (15x20s = 300s) / Last 10 Minute (30x20s = 600s)
        sleep((cfg?.vcs?.perf_max_samples*20)*1000)
      }
    } catch (Exception e) {
      StackTraceUtils.deepSanitize(e)
      log.error "runAsDaemon exception: ${getStackTrace(e)}"
      throw new RuntimeException("runAsDaemon exception: ${getStackTrace(e)}")
    }
  }


  /**
   * Main execution loop
   *
   */
  static main(args) {
    def main = new vSphere2Graphite()

    CliBuilder cli = new CliBuilder(usage: '[-h] [-dc] [-dm] [-sf <(1..60) Minutes>] [No paramaters Run as Daemon]')
    cli.h(longOpt:'help', 'Usage information')
    cli.dc(longOpt:'dumpcounters', 'Dump available counters, OPTIONAL', required:false)
    cli.dm(longOpt:'dumpmetrics', 'Dump Metrics, OPTIONAL', required:false)
    cli.sf(longOpt:'startfrom', 'Start from last samples (Real-Time (1..60)min), OPTIONAL', argName:'Mins', required:false, type:int, args:1)

    OptionAccessor opt = cli.parse(args)
    if (!opt) return

    // Parse 'Start from' parameter
    if (opt.sf){
      // Maximum allowed samples is 180 (real-time)
      if ((1..60).contains(opt.sf.toInteger())){
        main.startFromExecTime = new TimeDuration(0, opt.sf.toInteger(), 0, 0)
      } else {
        println "The start from parameter '${opt.sf}' is Out of range (1..60)"
        return
      }
    }

    if (opt.dc){
      main.dumpCounters()
    } else if (opt.dm){
      main.dumpMetrics()
    } else if (opt.h | opt.arguments().size() != 0){
      cli.usage()
    } else {
      main.runAsDaemon()
    }
  }
}
