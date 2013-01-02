#!/bin/bash
#
# chkconfig: 2345 85 15
# description: Java deamon script
# processname: vSphere2Graphite
# pidfile: /var/run/NNMiPerf2Graphite.pid

# Install:
#    chkconfig --add vSphere2Graphite
#    chkconfig --list |grep -i vSphere2Graphite
#    service vSphere2Graphite start
#
# source function library
. /etc/init.d/functions


# Set this to your Java installation
JAVA_HOME=/usr/java/latest
JMX_OPTS='-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9191 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.local.only=false'
JAVA_OPTS="-server -Xmx800M -Xms350M -XX:MaxPermSize=80m -XX:+CMSClassUnloadingEnabled -XX:+UseG1GC -XX:+AggressiveOpts $JMX_OPTS"

serviceName="vSphere2Graphite"                             # service name
serviceUser="root"                                         # OS user name for the service
serviceGroup="wheel"                                       # OS group name for the service
debugLevel="PROD"                                          # Debuging levels: PROD, DEV, DEBUG
applDir="/opt/vSphere2Graphite"                            # home directory of the service application
serviceUserHome="/home/$serviceUser"                       # home directory of the service user
serviceLogFile="$applDir/logs/${serviceName}_init.log"     # log file for StdOut/StdErr
maxShutdownTime=15                                         # maximum number of seconds to wait for the daemon to terminate normally
pidFile="/var/run/$serviceName.pid"                        # name of PID file (PID = process ID number)
javaCommand="java"                                         # name of the Java launcher without the path
javaExe="$JAVA_HOME/bin/$javaCommand"                      # file name of the Java application launcher executable
javaArgs="-Dapp.env=$debugLevel -jar $applDir/vSphere2Graphite.jar"             # arguments for Java launcher
javaCommandLine="$javaExe $JAVA_OPTS $javaArgs"            # command line to start the Java service application
javaCommandLineKeyword="${serviceName}.jar"                # a keyword that occurs on the commandline, used to detect an already running service process and to distinguish it from others


# Makes the file $1 writable by the group $serviceGroup.
makeFileWritable() {
   local filename="$1"
   touch $filename || return 1
   chgrp $serviceGroup $filename || return 1
   chmod g+w $filename || return 1
   return 0; }

# Returns 0 if the process with PID $1 is running.
checkProcessIsRunning() {
   local pid="$1"
   if [ -z "$pid" -o "$pid" == " " ]; then return 1; fi
   if [ ! -e /proc/$pid ]; then return 1; fi
   return 0; }

# Returns 0 if the process with PID $1 is our Java service process.
checkProcessIsOurService() {
   local pid="$1"
   if [ "$(ps -p $pid --no-headers -o comm)" != "$javaCommand" ]; then return 1; fi
   grep -q --binary -F "$javaCommandLineKeyword" /proc/$pid/cmdline
   if [ $? -ne 0 ]; then return 1; fi
   return 0; }

# Returns 0 when the service is running and sets the variable $pid to the PID.
getServicePID() {
   if [ ! -f $pidFile ]; then return 1; fi
   pid="$(<$pidFile)"
   checkProcessIsRunning $pid || return 1
   checkProcessIsOurService $pid || return 1
   return 0; }

startServiceProcess() {
   cd $applDir || return 1
   rm -f $pidFile
   makeFileWritable $pidFile || return 1
   makeFileWritable $serviceLogFile || return 1
   cmd="cd $applDir & nohup $javaCommandLine >>$serviceLogFile 2>&1 & echo \$! >$pidFile"
   su -m $serviceUser -c "$cmd" || return 1
   sleep 0.1
   pid="$(<$pidFile)"
   if checkProcessIsRunning $pid; then :; else
      echo -ne "\n$serviceName start failed, see logfile."
      return 1
   fi
   return 0; }

stopServiceProcess() {
   kill $pid || return 1
   for ((i=0; i<maxShutdownTime*10; i++)); do
      checkProcessIsRunning $pid
      if [ $? -ne 0 ]; then
         rm -f $pidFile
         return 0
         fi
      sleep 0.1
      done
   echo -e "\n$serviceName did not terminate within $maxShutdownTime seconds, sending SIGKILL..."
   kill -s KILL $pid || return 1
   local killWaitTime=15
   for ((i=0; i<killWaitTime*10; i++)); do
      checkProcessIsRunning $pid
      if [ $? -ne 0 ]; then
         rm -f $pidFile
         return 0
         fi
      sleep 0.1
      done
   echo "Error: $serviceName could not be stopped within $maxShutdownTime+$killWaitTime seconds!"
   return 1; }

startService() {
   getServicePID
   if [ $? -eq 0 ]; then echo -n "$serviceName is already running"; RETVAL=0; return 0; fi
   echo -n "Starting $serviceName   "
   startServiceProcess
   if [ $? -ne 0 ]; then RETVAL=1; echo "failed"; return 1; fi
   echo "started PID=$pid"
   RETVAL=0
   return 0; }

stopService() {
   getServicePID
   if [ $? -ne 0 ]; then echo -n "$serviceName is not running"; RETVAL=0; echo ""; return 0; fi
   echo -n "Stopping $serviceName   "
   stopServiceProcess
   if [ $? -ne 0 ]; then RETVAL=1; echo "failed"; return 1; fi
   echo "stopped PID=$pid"
   RETVAL=0
   return 0; }

checkServiceStatus() {
   echo -n "Checking for $serviceName:   "
   if getServicePID; then
    echo "running PID=$pid"
    RETVAL=0
   else
    echo "stopped"
    RETVAL=3
   fi
   return 0; }




# See how we were called.
case "$1" in
  start)
        startService
        ;;
  stop)
        stopService
        ;;
  status)
        checkServiceStatus
        ;;
  restart|reload)
        stopService && startService
        ;;
  *)
        echo $"Usage: $0 {start|stop|status|restart}"
        exit 1
esac

exit $?
