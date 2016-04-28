package eu.daiad.web.repository.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;
import org.joda.time.DateTimeZone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import eu.daiad.web.hbase.HBaseConnectionManager;
import eu.daiad.web.model.amphiro.AmphiroAbstractDataPoint;
import eu.daiad.web.model.amphiro.AmphiroAbstractSession;
import eu.daiad.web.model.amphiro.AmphiroDataPoint;
import eu.daiad.web.model.amphiro.AmphiroDataSeries;
import eu.daiad.web.model.amphiro.AmphiroMeasurement;
import eu.daiad.web.model.amphiro.AmphiroMeasurementCollection;
import eu.daiad.web.model.amphiro.AmphiroMeasurementIndexIntervalQuery;
import eu.daiad.web.model.amphiro.AmphiroMeasurementIndexIntervalQueryResult;
import eu.daiad.web.model.amphiro.AmphiroSession;
import eu.daiad.web.model.amphiro.AmphiroSessionCollection;
import eu.daiad.web.model.amphiro.AmphiroSessionCollectionIndexIntervalQuery;
import eu.daiad.web.model.amphiro.AmphiroSessionCollectionIndexIntervalQueryResult;
import eu.daiad.web.model.amphiro.AmphiroSessionDeleteAction;
import eu.daiad.web.model.amphiro.AmphiroSessionDetails;
import eu.daiad.web.model.amphiro.AmphiroSessionIndexIntervalQuery;
import eu.daiad.web.model.amphiro.AmphiroSessionIndexIntervalQueryResult;
import eu.daiad.web.model.amphiro.AmphiroSessionUpdate;
import eu.daiad.web.model.amphiro.AmphiroSessionUpdateCollection;
import eu.daiad.web.model.error.ApplicationException;
import eu.daiad.web.model.error.DataErrorCode;
import eu.daiad.web.model.error.SharedErrorCode;

@Repository()
public class HBaseAmphiroIndexOrderedRepository extends HBaseBaseRepository implements IAmphiroIndexOrderedRepository {

	private static final Log logger = LogFactory.getLog(HBaseAmphiroIndexOrderedRepository.class);

	private final String ERROR_RELEASE_RESOURCES = "Failed to release resources";

	private enum EnumTimeInterval {
		UNDEFINED(0), HOUR(3600), DAY(86400);

		private final int value;

		private EnumTimeInterval(int value) {
			this.value = value;
		}

		public int getValue() {
			return this.value;
		}
	}

	@Value("${hbase.data.time.partitions}")
	private short timePartitions;

	@Value("${scanner.cache.size}")
	private int scanCacheSize = 1;

	private final String amphiroTableSessionIndex = "daiad:amphiro-sessions-index-v2";

	private final String amphiroTableMeasurements = "daiad:amphiro-measurements-v2";

	private final String amphiroTableSessionByTime = "daiad:amphiro-sessions-by-time-v2";

	private final String amphiroTableSessionByUser = "daiad:amphiro-sessions-by-user-v2";

	private final String columnFamilyName = "cf";

	@Autowired
	private HBaseConnectionManager connection;

	private void refreshSessionTimestampIndex(UUID userKey, AmphiroMeasurementCollection data) throws Exception {
		Table table = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableSessionIndex);

			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);
			byte[] columnQualifier = Bytes.toBytes("ts");

			for (int i = 0, length = data.getSessions().size(); i < length; i++) {
				AmphiroSession s = data.getSessions().get(i);

				if (!s.isHistory()) {
					byte[] userKeyBytes = userKey.toString().getBytes("UTF-8");
					byte[] userKeyHash = md.digest(userKeyBytes);

					byte[] deviceKey = data.getDeviceKey().toString().getBytes("UTF-8");
					byte[] deviceKeyHash = md.digest(deviceKey);

					byte[] sessionIdBytes = Bytes.toBytes(s.getId());

					byte[] rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];

					System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
					System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
					System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
									sessionIdBytes.length);

					// Get index entry for the specific session Id
					Get get = new Get(rowKey);
					Result existingIndexEntry = table.get(get);

					if (existingIndexEntry.getRow() == null) {
						Put put = new Put(rowKey);
						put.addColumn(columnFamily, columnQualifier, Bytes.toBytes(s.getTimestamp()));

						table.put(put);
					}
				}
			}
		} finally {
			try {
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	private void storeSessionByUser(UUID userKey, AmphiroMeasurementCollection data,
					AmphiroSessionUpdateCollection updates) throws Exception {
		Table table = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableSessionByUser);
			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			for (int i = data.getSessions().size() - 1; i >= 0; i--) {
				AmphiroSession s = data.getSessions().get(i);

				byte[] rowKey;

				byte[] userKeyBytes = userKey.toString().getBytes("UTF-8");
				byte[] userKeyHash = md.digest(userKeyBytes);

				byte[] deviceKey = data.getDeviceKey().toString().getBytes("UTF-8");
				byte[] deviceKeyHash = md.digest(deviceKey);

				byte[] sessionIdBytes = Bytes.toBytes(Long.MAX_VALUE - s.getId());

				// Construct row key
				rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];

				System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
								sessionIdBytes.length);

				// Get existing row if any exists and set update properties
				Get get = new Get(rowKey);
				Result existingRow = table.get(get);

				boolean isHistorical = false;
				Long existingTimestamp = null;

				if (existingRow.getRow() != null) {
					NavigableMap<byte[], byte[]> map = existingRow.getFamilyMap(columnFamily);

					for (Entry<byte[], byte[]> entry : map.entrySet()) {
						String qualifier = Bytes.toString(entry.getKey());

						switch (qualifier) {
							case "s:h":
								isHistorical = Bytes.toBoolean(entry.getValue());
								break;
							case "s:t":
								existingTimestamp = Bytes.toLong(entry.getValue());
								break;
						}

					}
				}

				// Insert row
				Put put = new Put(rowKey);
				byte[] column;

				if (existingRow.getRow() == null) {
					column = Bytes.toBytes("s:t");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.getTimestamp()));

					column = Bytes.toBytes("s:h");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.isHistory()));

					column = Bytes.toBytes("s:u");
					put.addColumn(columnFamily, column, Bytes.toBytes(false));

					column = Bytes.toBytes("m:v");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.getVolume()));

					column = Bytes.toBytes("m:e");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.getEnergy()));

					column = Bytes.toBytes("m:d");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.getDuration()));

					column = Bytes.toBytes("m:t");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.getTemperature()));

					column = Bytes.toBytes("m:f");
					put.addColumn(columnFamily, column, Bytes.toBytes(s.getFlow()));

					for (int p = 0, count = s.getProperties().size(); p < count; p++) {
						column = Bytes.toBytes(s.getProperties().get(p).getKey());
						put.addColumn(columnFamily, column,
										s.getProperties().get(p).getValue().getBytes(StandardCharsets.UTF_8));
					}

					table.put(put);
				} else {
					if (isHistorical) {
						if (s.isHistory()) {
							// Keep the less recent time-stamp
							if (s.getTimestamp() < existingTimestamp) {
								column = Bytes.toBytes("s:t");
								put.addColumn(columnFamily, column, Bytes.toBytes(s.getTimestamp()));

								table.put(put);

								// Propagate update to time indexed table
								s.setDelete(new AmphiroSessionDeleteAction(existingTimestamp));
							}
						} else {
							// Real-time session data delayed arrival

							// Mark session as updated
							column = Bytes.toBytes("s:u");
							put.addColumn(columnFamily, column, Bytes.toBytes(true));

							// Mark session as real-time
							column = Bytes.toBytes("s:h");
							put.addColumn(columnFamily, column, Bytes.toBytes(false));

							// Update time stamp
							column = Bytes.toBytes("s:t");
							put.addColumn(columnFamily, column, Bytes.toBytes(s.getTimestamp()));

							// Store existing time stamp
							column = Bytes.toBytes("h:t");
							put.addColumn(columnFamily, column, Bytes.toBytes(existingTimestamp));

							table.put(put);

							// Propagate update to time indexed table
							s.setDelete(new AmphiroSessionDeleteAction(existingTimestamp));
						}

					} else {
						// Session already exists and is a real-time one!
						updates.getUpdates().add(new AmphiroSessionUpdate(s.getId(), existingTimestamp.longValue()));

						// Stop propagation to time indexed table
						data.removeSession(i);
					}
				}
			}
		} finally {
			try {
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	private void storeSessionByTime(UUID userKey, AmphiroMeasurementCollection data) throws Exception {
		Table table = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableSessionByTime);
			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			for (int i = 0; i < data.getSessions().size(); i++) {
				AmphiroSession s = data.getSessions().get(i);

				long timestamp, offset, timeBucket;

				byte[] partitionBytes, timeBucketBytes, rowKey;

				byte[] userKeyBytes = userKey.toString().getBytes("UTF-8");
				byte[] userKeyHash = md.digest(userKeyBytes);

				byte[] deviceKey = data.getDeviceKey().toString().getBytes("UTF-8");
				byte[] deviceKeyHash = md.digest(deviceKey);

				byte[] sessionIdBytes = Bytes.toBytes(s.getId());

				// Delete existing record
				if (s.getDelete() != null) {
					for (short partitionIndex = 0; partitionIndex < this.timePartitions; partitionIndex++) {
						partitionBytes = Bytes.toBytes(partitionIndex);

						timestamp = s.getDelete().getTimestamp() / 1000;
						offset = timestamp % EnumTimeInterval.DAY.getValue();

						timeBucket = timestamp - offset;
						timeBucketBytes = Bytes.toBytes(timeBucket);

						rowKey = new byte[partitionBytes.length + timeBucketBytes.length + userKeyHash.length
										+ deviceKeyHash.length + sessionIdBytes.length];

						System.arraycopy(partitionBytes, 0, rowKey, 0, partitionBytes.length);
						System.arraycopy(timeBucketBytes, 0, rowKey, partitionBytes.length, timeBucketBytes.length);
						System.arraycopy(userKeyHash, 0, rowKey, (partitionBytes.length + timeBucketBytes.length),
										userKeyHash.length);
						System.arraycopy(deviceKeyHash, 0, rowKey,
										(partitionBytes.length + timeBucketBytes.length + userKeyHash.length),
										deviceKeyHash.length);
						System.arraycopy(sessionIdBytes, 0, rowKey, (partitionBytes.length + timeBucketBytes.length
										+ userKeyHash.length + deviceKeyHash.length), sessionIdBytes.length);

						Delete delete = new Delete(rowKey);
						table.delete(delete);
					}
				}

				// Insert new record
				short partition = (short) (s.getTimestamp() % this.timePartitions);
				partitionBytes = Bytes.toBytes(partition);

				timestamp = s.getTimestamp() / 1000;
				offset = timestamp % EnumTimeInterval.DAY.getValue();
				timeBucket = timestamp - offset;

				timeBucketBytes = Bytes.toBytes(timeBucket);

				rowKey = new byte[partitionBytes.length + timeBucketBytes.length + userKeyHash.length
								+ deviceKeyHash.length + sessionIdBytes.length];

				System.arraycopy(partitionBytes, 0, rowKey, 0, partitionBytes.length);
				System.arraycopy(timeBucketBytes, 0, rowKey, partitionBytes.length, timeBucketBytes.length);
				System.arraycopy(userKeyHash, 0, rowKey, (partitionBytes.length + timeBucketBytes.length),
								userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey,
								(partitionBytes.length + timeBucketBytes.length + userKeyHash.length),
								deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (partitionBytes.length + timeBucketBytes.length
								+ userKeyHash.length + deviceKeyHash.length), sessionIdBytes.length);

				Put put = new Put(rowKey);

				byte[] column;

				column = Bytes.toBytes("s:offset");
				put.addColumn(columnFamily, column, Bytes.toBytes((int) offset));

				column = Bytes.toBytes("m:v");
				put.addColumn(columnFamily, column, Bytes.toBytes(s.getVolume()));

				column = Bytes.toBytes("m:e");
				put.addColumn(columnFamily, column, Bytes.toBytes(s.getEnergy()));

				column = Bytes.toBytes("m:d");
				put.addColumn(columnFamily, column, Bytes.toBytes(s.getDuration()));

				column = Bytes.toBytes("m:t");
				put.addColumn(columnFamily, column, Bytes.toBytes(s.getTemperature()));

				column = Bytes.toBytes("m:f");
				put.addColumn(columnFamily, column, Bytes.toBytes(s.getFlow()));

				column = Bytes.toBytes("s:h");
				put.addColumn(columnFamily, column, Bytes.toBytes(s.isHistory()));

				for (int p = 0, count = s.getProperties().size(); p < count; p++) {
					column = Bytes.toBytes(s.getProperties().get(p).getKey());
					put.addColumn(columnFamily, column,
									s.getProperties().get(p).getValue().getBytes(StandardCharsets.UTF_8));
				}

				table.put(put);
			}
		} finally {
			try {
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	private void preProcessData(AmphiroMeasurementCollection data) {
		try {
			// Sort sessions
			ArrayList<AmphiroSession> sessions = data.getSessions();

			Collections.sort(sessions, new Comparator<AmphiroSession>() {

				@Override
				public int compare(AmphiroSession s1, AmphiroSession s2) {
					if (s1.getId() == s2.getId()) {
						throw new RuntimeException("Session id must be unique.");
					} else if (s1.getId() < s2.getId()) {
						return -1;
					} else {
						return 1;
					}
				}
			});

			// Sort measurements
			ArrayList<AmphiroMeasurement> measurements = data.getMeasurements();

			if ((measurements != null) && (measurements.size() > 0)) {

				Collections.sort(measurements, new Comparator<AmphiroMeasurement>() {

					@Override
					public int compare(AmphiroMeasurement m1, AmphiroMeasurement m2) {
						if (m1.getSessionId() == m2.getSessionId()) {
							if (m1.getIndex() == m2.getIndex()) {
								throw new RuntimeException("Session measurement indexes must be unique.");
							}
							if (m1.getTimestamp() == m2.getTimestamp()) {
								throw new RuntimeException("Session measurement timestamps must be unique.");
							}
							if (m1.getIndex() < m2.getIndex()) {
								if (m1.getTimestamp() > m2.getTimestamp()) {
									throw new RuntimeException(
													"Session measurements timestamp and index has ambiguous orderning.");
								}
								return -1;
							} else {
								if (m1.getTimestamp() < m2.getTimestamp()) {
									throw new RuntimeException(
													"Session measurements timestamp and index has ambiguous orderning.");
								}
								return 1;
							}
						} else if (m1.getSessionId() < m2.getSessionId()) {
							return -1;
						} else {
							return 1;
						}
					}
				});

				// Compute difference for volume and energy
				for (int i = measurements.size() - 1; i > 0; i--) {
					if (measurements.get(i).getSessionId() == measurements.get(i - 1).getSessionId()) {
						// Set volume
						float diff = measurements.get(i).getVolume() - measurements.get(i - 1).getVolume();
						measurements.get(i).setVolume((float) Math.round(diff * 1000f) / 1000f);
						// Set energy
						diff = measurements.get(i).getEnergy() - measurements.get(i - 1).getEnergy();
						measurements.get(i).setEnergy((float) Math.round(diff * 1000f) / 1000f);
					}
				}

				// Set session for every measurement
				for (AmphiroMeasurement m : measurements) {
					for (AmphiroSession s : sessions) {
						if (m.getSessionId() == s.getId()) {
							if (s.isHistory()) {
								throw new ApplicationException(DataErrorCode.HISTORY_SESSION_MEASUREMENT_FOUND).set(
												"session", m.getSessionId()).set("index", m.getIndex());
							}
							m.setSession(s);
							break;
						}
					}
					if (m.getSession() == null) {
						throw new ApplicationException(DataErrorCode.NO_SESSION_FOUND_FOR_MEASUREMENT).set("session",
										m.getSessionId()).set("index", m.getIndex());
					}
				}
			}
		} catch (ApplicationException ex) {
			logger.warn(this.jsonToString(data));

			throw ex;
		}
	}

	private void storeMeasurements(UUID userKey, AmphiroMeasurementCollection data) throws Exception {
		Table table = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableMeasurements);
			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			for (int i = 0; i < data.getMeasurements().size(); i++) {
				AmphiroMeasurement m = data.getMeasurements().get(i);

				if (m.getVolume() <= 0) {
					continue;
				}

				byte[] userKeyBytes = userKey.toString().getBytes("UTF-8");
				byte[] userKeyHash = md.digest(userKeyBytes);

				byte[] deviceKey = data.getDeviceKey().toString().getBytes("UTF-8");
				byte[] deviceKeyHash = md.digest(deviceKey);

				byte[] sessionIdBytes = Bytes.toBytes(Long.MAX_VALUE - m.getSessionId());
				byte[] indexBytes = Bytes.toBytes(m.getIndex());

				byte[] rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];
				byte[] column = null;

				System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
								sessionIdBytes.length);

				Put p = new Put(rowKey);

				column = this.concatenate(indexBytes, this.appendLength(Bytes.toBytes("ts")));
				p.addColumn(columnFamily, column, Bytes.toBytes(m.getTimestamp()));

				column = this.concatenate(indexBytes, this.appendLength(Bytes.toBytes("v")));
				p.addColumn(columnFamily, column, Bytes.toBytes(m.getVolume()));

				column = this.concatenate(indexBytes, this.appendLength(Bytes.toBytes("e")));
				p.addColumn(columnFamily, column, Bytes.toBytes(m.getEnergy()));

				column = this.concatenate(indexBytes, this.appendLength(Bytes.toBytes("t")));
				p.addColumn(columnFamily, column, Bytes.toBytes(m.getTemperature()));

				column = this.concatenate(indexBytes, this.appendLength(Bytes.toBytes("h")));
				p.addColumn(columnFamily, column, Bytes.toBytes(m.isHistory()));

				table.put(p);
			}
		} finally {
			try {
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	@Override
	public AmphiroSessionUpdateCollection storeData(UUID userKey, AmphiroMeasurementCollection data)
					throws ApplicationException {
		AmphiroSessionUpdateCollection updates = new AmphiroSessionUpdateCollection();

		try {
			if ((data != null) && (data.getSessions() != null) && (data.getSessions().size() != 0)) {
				this.preProcessData(data);

				this.refreshSessionTimestampIndex(userKey, data);

				this.storeSessionByUser(userKey, data, updates);
				this.storeSessionByTime(userKey, data);

				if ((data.getMeasurements() != null) && (data.getMeasurements().size() != 0)) {
					this.storeMeasurements(userKey, data);
				}
			}
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		}

		return updates;
	}

	private byte[] appendLength(byte[] array) throws Exception {
		byte[] length = { (byte) array.length };

		return concatenate(length, array);
	}

	private byte[] concatenate(byte[] a, byte[] b) {
		int lengthA = a.length;
		int lengthB = b.length;
		byte[] concat = new byte[lengthA + lengthB];
		System.arraycopy(a, 0, concat, 0, lengthA);
		System.arraycopy(b, 0, concat, lengthA, lengthB);
		return concat;
	}

	@Override
	public AmphiroMeasurementIndexIntervalQueryResult searchMeasurements(DateTimeZone timezone,
					AmphiroMeasurementIndexIntervalQuery query) {
		AmphiroMeasurementIndexIntervalQueryResult data = new AmphiroMeasurementIndexIntervalQueryResult();

		long startIndex = Long.MAX_VALUE;
		long endIndex = Long.MAX_VALUE;
		int maxTotalSessions = Integer.MAX_VALUE;

		switch (query.getType()) {
			case ABSOLUTE:
				startIndex -= query.getEndIndex();
				endIndex -= query.getStartIndex();
				break;
			case SLIDING:
				startIndex = 0;
				maxTotalSessions = query.getLength();
				break;
			default:
				return data;
		}

		Table table = null;
		ResultScanner scanner = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableMeasurements);
			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			byte[] userKey = query.getUserKey().toString().getBytes("UTF-8");
			byte[] userKeyHash = md.digest(userKey);

			UUID deviceKeys[] = query.getDeviceKey();

			for (int deviceIndex = 0; deviceIndex < deviceKeys.length; deviceIndex++) {
				AmphiroDataSeries series = new AmphiroDataSeries(deviceKeys[deviceIndex]);

				data.getSeries().add(series);

				if (startIndex > endIndex) {
					continue;
				}

				Scan scan = new Scan();
				scan.addFamily(columnFamily);

				byte[] deviceKey = deviceKeys[deviceIndex].toString().getBytes("UTF-8");
				byte[] deviceKeyHash = md.digest(deviceKey);

				byte[] sessionIdBytes = Bytes.toBytes(startIndex);

				byte[] rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];

				System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
								sessionIdBytes.length);

				scan.setStartRow(rowKey);

				sessionIdBytes = Bytes.toBytes(endIndex);

				rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];

				System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
								sessionIdBytes.length);

				scan.setStopRow(this.calculateTheClosestNextRowKeyForPrefix(rowKey));

				scanner = table.getScanner(scan);

				ArrayList<eu.daiad.web.model.amphiro.AmphiroDataPoint> points = new ArrayList<eu.daiad.web.model.amphiro.AmphiroDataPoint>();

				int totalSessions = 0;
				long currentSessionId = -1;

				for (Result r = scanner.next(); r != null; r = scanner.next()) {
					NavigableMap<byte[], byte[]> map = r.getFamilyMap(columnFamily);

					long sessionId = Long.MAX_VALUE - Bytes.toLong(Arrays.copyOfRange(r.getRow(), 32, 40));
					if (currentSessionId != sessionId) {
						currentSessionId = sessionId;
						totalSessions++;
					}
					if (totalSessions >= maxTotalSessions) {
						break;
					}

					int currentIndex = -1;
					eu.daiad.web.model.amphiro.AmphiroDataPoint point = null;

					for (Entry<byte[], byte[]> entry : map.entrySet()) {
						int currentColumnIndex = Bytes.toInt(Arrays.copyOfRange(entry.getKey(), 0, 4));

						if (currentIndex != currentColumnIndex) {
							currentIndex = currentColumnIndex;

							point = new eu.daiad.web.model.amphiro.AmphiroDataPoint();
							point.setSessionId(sessionId);
							point.setIndex(currentIndex);

							points.add(point);
						}

						int qualifierLength = Arrays.copyOfRange(entry.getKey(), 4, 5)[0];
						byte[] qualifierBytes = Arrays.copyOfRange(entry.getKey(), 5, 5 + qualifierLength);
						String qualifier = Bytes.toString(qualifierBytes);

						switch (qualifier) {
							case "h":
								point.setHistory(Bytes.toBoolean(entry.getValue()));
								break;
							case "v":
								point.setVolume(Bytes.toFloat(entry.getValue()));
								break;
							case "e":
								point.setEnergy(Bytes.toFloat(entry.getValue()));
								break;
							case "t":
								point.setTemperature(Bytes.toFloat(entry.getValue()));
								break;
							case "ts":
								point.setTimestamp(Bytes.toLong(entry.getValue()));
								break;
						}
					}
				}

				scanner.close();
				scanner = null;

				series.setPoints(points, timezone);

				Collections.sort(series.getPoints(), new Comparator<AmphiroAbstractDataPoint>() {

					@Override
					public int compare(AmphiroAbstractDataPoint o1, AmphiroAbstractDataPoint o2) {
						AmphiroDataPoint m1 = (AmphiroDataPoint) o1;
						AmphiroDataPoint m2 = (AmphiroDataPoint) o2;

						if (m1.getSessionId() < m2.getSessionId()) {
							return -1;
						} else if (m1.getSessionId() > m2.getSessionId()) {
							return 1;
						}
						if (m1.getIndex() < m2.getIndex()) {
							return -1;
						} else if (m1.getIndex() < m2.getIndex()) {
							return 1;
						}
						return 0;
					}

				});
			}

			return data;
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		} finally {
			try {
				if (scanner != null) {
					scanner.close();
					scanner = null;
				}
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	@Override
	public AmphiroSessionCollectionIndexIntervalQueryResult searchSessions(String[] names, DateTimeZone timezone,
					AmphiroSessionCollectionIndexIntervalQuery query) {
		AmphiroSessionCollectionIndexIntervalQueryResult data = new AmphiroSessionCollectionIndexIntervalQueryResult();

		long startIndex = Long.MAX_VALUE;
		long endIndex = Long.MAX_VALUE;
		int maxTotalSessions = Integer.MAX_VALUE;

		switch (query.getType()) {
			case ABSOLUTE:
				startIndex -= query.getEndIndex();
				endIndex -= query.getStartIndex();
				break;
			case SLIDING:
				startIndex = 0;
				maxTotalSessions = query.getLength();
				break;
			default:
				return data;
		}

		Table table = null;
		ResultScanner scanner = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableSessionByUser);
			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			byte[] userKey = query.getUserKey().toString().getBytes("UTF-8");
			byte[] userKeyHash = md.digest(userKey);

			UUID deviceKeys[] = query.getDeviceKey();

			int totalSessions = 0;

			for (int deviceIndex = 0; deviceIndex < deviceKeys.length; deviceIndex++) {
				AmphiroSessionCollection collection = new AmphiroSessionCollection(deviceKeys[deviceIndex],
								names[deviceIndex]);

				data.getDevices().add(collection);

				if (startIndex > endIndex) {
					continue;
				}

				ArrayList<AmphiroSession> sessions = new ArrayList<AmphiroSession>();

				byte[] deviceKey = deviceKeys[deviceIndex].toString().getBytes("UTF-8");
				byte[] deviceKeyHash = md.digest(deviceKey);

				Scan scan = new Scan();
				scan.addFamily(columnFamily);

				byte[] sessionIdBytes = Bytes.toBytes(startIndex);

				byte[] rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];
				System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
								sessionIdBytes.length);

				scan.setStartRow(rowKey);

				sessionIdBytes = Bytes.toBytes(endIndex);

				rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];
				System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
				System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
				System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
								sessionIdBytes.length);

				scan.setStopRow(this.calculateTheClosestNextRowKeyForPrefix(rowKey));

				scanner = table.getScanner(scan);

				for (Result r = scanner.next(); r != null; r = scanner.next()) {
					NavigableMap<byte[], byte[]> map = r.getFamilyMap(columnFamily);

					long sessionId = Long.MAX_VALUE - Bytes.toLong(Arrays.copyOfRange(r.getRow(), 32, 40));

					AmphiroSession session = new AmphiroSession();
					session.setId(sessionId);

					for (Entry<byte[], byte[]> entry : map.entrySet()) {

						String qualifier = Bytes.toString(entry.getKey());

						switch (qualifier) {
							case "s:t":
								session.setTimestamp(Bytes.toLong(entry.getValue()));
								break;
							case "s:h":
								session.setHistory(Bytes.toBoolean(entry.getValue()));
								break;
							case "s:u":
								// Ignore
								break;
							case "h:t":
								// Ignore
								break;
							case "m:v":
								session.setVolume(Bytes.toFloat(entry.getValue()));
								break;
							case "m:e":
								session.setEnergy(Bytes.toFloat(entry.getValue()));
								break;
							case "m:d":
								session.setDuration(Bytes.toInt(entry.getValue()));
								break;
							case "m:t":
								session.setTemperature(Bytes.toFloat(entry.getValue()));
								break;
							case "m:f":
								session.setFlow(Bytes.toFloat(entry.getValue()));
								break;
							default:
								session.addProperty(qualifier, new String(entry.getValue(), StandardCharsets.UTF_8));
								break;
						}
					}

					if (totalSessions < maxTotalSessions) {
						sessions.add(session);
						totalSessions++;
					}
				}

				scanner.close();
				scanner = null;

				collection.addSessions(sessions, timezone);

				if (collection.getSessions().size() > 0) {
					Collections.sort(collection.getSessions(), new Comparator<AmphiroAbstractSession>() {
						@Override
						public int compare(AmphiroAbstractSession o1, AmphiroAbstractSession o2) {
							AmphiroSession s1 = (AmphiroSession) o1;
							AmphiroSession s2 = (AmphiroSession) o2;

							if (s1.getId() < s2.getId()) {
								return -1;
							} else if (s1.getId() > s2.getId()) {
								return 1;
							}
							return 0;
						}
					});
				}
			}

			return data;
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		} finally {
			try {
				if (scanner != null) {
					scanner.close();
					scanner = null;
				}
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	@Override
	public AmphiroSessionIndexIntervalQueryResult getSession(AmphiroSessionIndexIntervalQuery query) {
		AmphiroSessionIndexIntervalQueryResult data = new AmphiroSessionIndexIntervalQueryResult();

		Table table = null;
		ResultScanner scanner = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableSessionByUser);
			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			byte[] userKey = query.getUserKey().toString().getBytes("UTF-8");
			byte[] userKeyHash = md.digest(userKey);

			byte[] deviceKey = query.getDeviceKey().toString().getBytes("UTF-8");
			byte[] deviceKeyHash = md.digest(deviceKey);

			byte[] sessionIdBytes = Bytes.toBytes(Long.MAX_VALUE - query.getSessionId());

			byte[] rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];

			System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
			System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
			System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
							sessionIdBytes.length);

			Get get = new Get(rowKey);
			Result sessionResult = table.get(get);

			if (sessionResult.getRow() != null) {
				NavigableMap<byte[], byte[]> map = sessionResult.getFamilyMap(columnFamily);

				AmphiroSessionDetails session = new AmphiroSessionDetails();
				session.setId(query.getSessionId());

				for (Entry<byte[], byte[]> entry : map.entrySet()) {

					String qualifier = Bytes.toString(entry.getKey());

					switch (qualifier) {
						case "s:t":
							session.setTimestamp(Bytes.toLong(entry.getValue()));
							break;
						case "s:h":
							session.setHistory(Bytes.toBoolean(entry.getValue()));
							break;
						case "s:u":
							// Ignore
							break;
						case "h:t":
							// Ignore
							break;
						case "m:v":
							session.setVolume(Bytes.toFloat(entry.getValue()));
							break;
						case "m:e":
							session.setEnergy(Bytes.toFloat(entry.getValue()));
							break;
						case "m:d":
							session.setDuration(Bytes.toInt(entry.getValue()));
							break;
						case "m:t":
							session.setTemperature(Bytes.toFloat(entry.getValue()));
							break;
						case "m:f":
							session.setFlow(Bytes.toFloat(entry.getValue()));
							break;
						default:
							session.addProperty(qualifier, new String(entry.getValue(), StandardCharsets.UTF_8));
							break;
					}
				}

				session.setMeasurements(this.getSessionMeasurements(query));

				data.setSession(session);
			}

			return data;
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		} finally {
			try {
				if (scanner != null) {
					scanner.close();
					scanner = null;
				}
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

	private ArrayList<AmphiroMeasurement> getSessionMeasurements(AmphiroSessionIndexIntervalQuery query) {
		ArrayList<AmphiroMeasurement> measurements = new ArrayList<AmphiroMeasurement>();

		Table table = null;
		ResultScanner scanner = null;

		try {
			MessageDigest md = MessageDigest.getInstance("MD5");

			table = connection.getTable(this.amphiroTableMeasurements);

			byte[] columnFamily = Bytes.toBytes(this.columnFamilyName);

			byte[] userKey = query.getUserKey().toString().getBytes("UTF-8");
			byte[] userKeyHash = md.digest(userKey);

			byte[] deviceKey = query.getDeviceKey().toString().getBytes("UTF-8");
			byte[] deviceKeyHash = md.digest(deviceKey);

			byte[] sessionIdBytes = Bytes.toBytes(Long.MAX_VALUE - query.getSessionId());

			byte[] rowKey = new byte[userKeyHash.length + deviceKeyHash.length + sessionIdBytes.length];

			System.arraycopy(userKeyHash, 0, rowKey, 0, userKeyHash.length);
			System.arraycopy(deviceKeyHash, 0, rowKey, userKeyHash.length, deviceKeyHash.length);
			System.arraycopy(sessionIdBytes, 0, rowKey, (userKeyHash.length + deviceKeyHash.length),
							sessionIdBytes.length);

			Get get = new Get(rowKey);
			Result sessionResult = table.get(get);

			int currentIndex = -1;

			AmphiroMeasurement measurement = null;

			if (sessionResult.getRow() != null) {
				NavigableMap<byte[], byte[]> map = sessionResult.getFamilyMap(columnFamily);

				for (Entry<byte[], byte[]> entry : map.entrySet()) {
					int currentColumnIndex = Bytes.toInt(Arrays.copyOfRange(entry.getKey(), 0, 4));

					if (currentIndex != currentColumnIndex) {
						if (measurement != null) {
							measurements.add(measurement);
						}
						currentIndex = currentColumnIndex;

						measurement = new AmphiroMeasurement();
						measurement.setSessionId(query.getSessionId());
						measurement.setIndex(currentIndex);
					}

					int qualifierLength = Arrays.copyOfRange(entry.getKey(), 4, 5)[0];
					byte[] qualifierBytes = Arrays.copyOfRange(entry.getKey(), 5, 5 + qualifierLength);
					String qualifier = Bytes.toString(qualifierBytes);

					switch (qualifier) {
						case "h":
							measurement.setHistory(Bytes.toBoolean(entry.getValue()));
							break;
						case "v":
							measurement.setVolume(Bytes.toFloat(entry.getValue()));
							break;
						case "e":
							measurement.setEnergy(Bytes.toFloat(entry.getValue()));
							break;
						case "t":
							measurement.setTemperature(Bytes.toFloat(entry.getValue()));
							break;
						case "ts":
							measurement.setTimestamp(Bytes.toLong(entry.getValue()));
							break;
					}
				}
				if (measurement != null) {
					measurements.add(measurement);
				}
			}

			return measurements;
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		} finally {
			try {
				if (scanner != null) {
					scanner.close();
					scanner = null;
				}
				if (table != null) {
					table.close();
					table = null;
				}
			} catch (Exception ex) {
				logger.error(ERROR_RELEASE_RESOURCES, ex);
			}
		}
	}

}