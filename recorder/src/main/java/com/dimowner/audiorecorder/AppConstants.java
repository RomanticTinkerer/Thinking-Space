/*
 * Copyright 2018 Dmytro Ponomarenko
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

package com.dimowner.audiorecorder;

/**
 * LOCAL MODIFICATION (Thinking Space): upstream's AppConstants holds several hundred app-wide
 * constants. Only the single one the vendored recorder engine references is kept here, with its
 * upstream value (Dimowner/AudioRecorder @ 28ef142).
 */
public final class AppConstants {
	private AppConstants() {}

	public final static int RECORDING_VISUALIZATION_INTERVAL_NEW = 20; //mills
}
