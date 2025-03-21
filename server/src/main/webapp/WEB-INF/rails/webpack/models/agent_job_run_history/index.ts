/*
 * Copyright Thoughtworks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {ApiRequestBuilder, ApiResult, ApiVersion} from "helpers/api_request_builder";
import {SparkRoutes} from "helpers/spark_routes";
import {JobResult} from "../shared/job_result";
import {JobStateTransitionJSON} from "../shared/job_state_transition";

export interface AgentJobRunHistoryAPIJSON {
  uuid: string;
  jobs: JobRunHistoryJSON[];
  pagination: PaginationJSON;
}

export interface JobRunHistoryJSON {
  job_state_transitions: JobStateTransitionJSON[];
  job_name: string;
  stage_name: string;
  stage_counter: string;
  pipeline_name: string;
  pipeline_counter: number;
  result: JobResult;
  rerun: boolean;
}

export interface PaginationJSON {
  page_size: number;
  offset: number;
  total: number;
}

export class AgentJobRunHistoryAPI {
  static all(uuid: string, offset: number) {
    return ApiRequestBuilder.GET(SparkRoutes.agentJobRunHistoryAPIPath(uuid, offset), ApiVersion.latest)
                            .then((result: ApiResult<string>) => result.map((body) => {
                              return JSON.parse(body) as AgentJobRunHistoryAPIJSON;
                            }));
  }
}
