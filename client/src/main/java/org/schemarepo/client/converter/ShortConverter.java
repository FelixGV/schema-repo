/**
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
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.schemarepo.client.converter;

/**
 * To convert back and forth with Short.
 *
 * For most people this can be a reasonable choice for IDs. If anyone needs to
 * store more than 65K schemas for a single subject, they should probably take
 * a long hard look at how they're using the schema repo.
 */
public class ShortConverter implements Converter<Short> {
  @Override
  public Short fromString(String literal) {
    return Short.parseShort(literal);
  }

  @Override
  public String toString(Short strongType) {
    return strongType.toString();
  }
}
