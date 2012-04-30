#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


# packageBinNativeHadoop.sh - A simple script to help package a single set of 
#     native-hadoop libraries for bin-packaging

#
# Note: 
# This script relies on the following environment variables to function correctly:
#  * BASE_NATIVE_LIB_DIR
#  * BUILD_NATIVE_DIR
#  * DIST_LIB_DIR
#  * NATIVE_PLATFORM
# All these are setup by build.xml.
#

TAR='tar cf -'
UNTAR='tar xfBp -'
platform=$NATIVE_PLATFORM

# Copy the pre-built libraries in $BASE_NATIVE_LIB_DIR
if [ -d $BASE_NATIVE_LIB_DIR ]
then
  echo "Copying libraries in $BASE_NATIVE_LIB_DIR/$platform to $DIST_LIB_DIR/"
  if [ ! -d $BASE_NATIVE_LIB_DIR/$platform ]
  then 
    echo "ERROR: Platform $platform does not exist in $BASE_NATIVE_LIB_DIR"
    exit -1
  fi
  cd $BASE_NATIVE_LIB_DIR/$platform/
  $TAR *hadoop* | (cd $DIST_LIB_DIR/; $UNTAR)
fi

# Copy the custom-built libraries in $BUILD_NATIVE_DIR
if [ -d $BUILD_NATIVE_DIR ]
then 
  echo "Copying libraries in $BUILD_NATIVE_DIR/$platform/lib to $DIST_LIB_DIR/"
  if [ ! -d $BUILD_NATIVE_DIR/$platform ]
  then
    echo "ERROR: Platform $platform does not exist in $BUILD_NATIVE_DIR"
    exit -1
  fi
  cd $BUILD_NATIVE_DIR/$platform/lib
  $TAR *hadoop* | (cd $DIST_LIB_DIR/; $UNTAR)
fi
