import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.callfire.api.data.AnsweringMachineConfig;
import com.callfire.api.data.Broadcast;
import com.callfire.api.data.BroadcastSchedule;
import com.callfire.api.data.Call;
import com.callfire.api.data.DayOfWeek;
import com.callfire.api.data.LocalTimeZoneRestriction;
import com.callfire.api.data.Result;
import com.callfire.api.data.ToNumber;
import com.callfire.api.data.VoiceBroadcastConfig;
import com.callfire.api.service.wsdl.BroadcastServicePortType;
import com.callfire.api.service.wsdl.CallServicePortType;
import com.callfire.api.service.wsdl.ServiceFault;
import com.callfire.api.service.wsdl.http.soap12.CallFireApi;
import com.callfire.api.service.wsdl.http.soap12.CallFireApi.ServiceState;
import com.callfire.api.service.xsd.ActionQuery;
import com.callfire.api.service.xsd.BroadcastCommand;
import com.callfire.api.service.xsd.BroadcastRequest;
import com.callfire.api.service.xsd.CallQueryResult;
import com.callfire.api.service.xsd.ControlBroadcast;
import com.callfire.api.service.xsd.CreateBroadcastSchedule;
import com.callfire.api.service.xsd.CreateContactBatch;
import com.callfire.api.service.xsd.CreateSound;
import com.callfire.api.service.xsd.FaultCode;
import com.callfire.api.service.xsd.IdRequest;

/**
 * Example implementation of using CallFire's APIs to send
 * weekly PTA meeting reminders by using voice broadcasts
 * to parents of students at a K12 school. 
 * 
 * @author ross
 *
 */
public class WeeklyPtaVoiceBroadcastSample {
	private static final Logger LOG = Logger.getLogger(WeeklyPtaVoiceBroadcastSample.class.getName());
	
	// TODO may need better way to reference sounds?
	private static final File PTA_REMINDER_LIVE_SOUND = new File("../callfire-api-samples/src/resources/test-sound.mp3");
	private static final File PTA_REMINDER_MACHINE_SOUND = new File("../callfire-api-samples/src/resources/test-sound.mp3");
	
	private final String user;
	private final String password;
	private final int maxWaitMs;
	
	private final CallFireApi callFireApi;
	private final BroadcastServicePortType broadcastService;
	private final CallServicePortType callService;
	private final PropertiesConfiguration propertiesConfig;
	private Long scheduleId;
	
	public WeeklyPtaVoiceBroadcastSample() throws ConfigurationException {
		propertiesConfig = new PropertiesConfiguration("sample.properties");
		user = propertiesConfig.getString("user");
		password = propertiesConfig.getString("password");
		maxWaitMs = propertiesConfig.getInt("max_wait_ms");
		callFireApi = new CallFireApi(user, password, ServiceState.DEVELOPMENT);
		broadcastService = callFireApi.getBroadcastServicePort();
		callService = callFireApi.getCallServicePort();
	}

	private long createSound(File soundFile) throws ServiceFault {
		CreateSound sound = new CreateSound();
		sound.setName("some sound");
		
		DataSource source = new FileDataSource(soundFile);
		DataHandler dataHandler = new DataHandler(source);
		
		sound.setData(dataHandler);

		long soundId = callService.createSound(sound);
		return soundId;
	}
	
	private long createWeeklyBroadcast(long liveSoundId, long machineSoundId, String fromNumber) throws ServiceFault {
		
		Broadcast broadcast = new Broadcast();
		
		VoiceBroadcastConfig config = new VoiceBroadcastConfig();
		config.setAnsweringMachineConfig(AnsweringMachineConfig.AM_AND_LIVE);
		config.setLiveSoundId(liveSoundId);
		config.setMachineSoundId(machineSoundId);
		config.setFromNumber(fromNumber);
		
		// setting the local timezone restriction is a good idea to prevent
		// customers from getting messages outside of normal business
		// hours in their local timezone (determined by area code).
		LocalTimeZoneRestriction restriction = new LocalTimeZoneRestriction();
		restriction.setBeginTime(getCalendarFromHour(8)); // 8 am
		restriction.setEndTime(getCalendarFromHour(18));  // 6 pm
		config.setLocalTimeZoneRestriction(restriction);
		broadcast.setVoiceBroadcastConfig(config);
		
		broadcast.setName("monthly PTA meeting voice broadcast reminder");
		BroadcastRequest createRequest = new BroadcastRequest();
		createRequest.setBroadcast(broadcast);
		Long broadcastId = broadcastService.createBroadcast(createRequest);

		CreateBroadcastSchedule create = new CreateBroadcastSchedule();
		BroadcastSchedule schedule = new BroadcastSchedule();

		// weekly PTA meetings are on Mondays
		// and run for six weeks
		List<DayOfWeek> monday = new ArrayList<DayOfWeek>(1);
		monday.add(DayOfWeek.MONDAY);
		schedule.setDaysOfWeek(monday);
		schedule.setTimeZone(TimeZone.getDefault().getID());
		schedule.setStartTimeOfDay(getCalendarFromHour(8)); // 8 am
		schedule.setStopTimeOfDay(getCalendarFromHour(18)); // 6 pm
		schedule.setEndDate(getCalendarWeeksFromNow(6));
		
		create.setBroadcastId(broadcastId);
		create.setBroadcastSchedule(schedule);
		scheduleId = broadcastService.createBroadcastSchedule(create);

		return broadcastId;
	}
	
	private long sendBatch(String name, Collection<Object> toNumbers, long broadcastId)
			throws ServiceFault {
		Long batchId = broadcastService.createContactBatch(createBatch(name, toNumbers, broadcastId));
		
		// start the broadcast
		ControlBroadcast control = new ControlBroadcast();
		control.setId(broadcastId);
		control.setCommand(BroadcastCommand.START);
		broadcastService.controlBroadcast(control);
		return batchId;
	}

	private CreateContactBatch createBatch(String name, Collection<Object> toNumbers, Long broadcastId) {
		CreateContactBatch createBatch = new CreateContactBatch();
		createBatch.setName(name);
		createBatch.setBroadcastId(broadcastId);
		for (Object toNumber : toNumbers) {
			ToNumber toNumberElem = new ToNumber();
			toNumberElem.setValue((String)toNumber);
			createBatch.getToNumber().add(toNumberElem);
		}
		createBatch.setScrubBroadcastDuplicates(false);
		return createBatch;
	}
	
	private boolean pollForResponse(long broadcastId, List<Object> toNumbers, Long batchId) {
		LOG.info("polling for responses for batch: " + batchId);
		
		final int sleepInterval = 2000;
		int totalWait = 0;

		ActionQuery callQuery = new ActionQuery();
		callQuery.setBroadcastId(broadcastId);

		while (totalWait < maxWaitMs) {
			try {
				Thread.sleep(sleepInterval);
				totalWait += sleepInterval;
			}
			catch (InterruptedException e) {
				LOG.log(Level.FINE, "interrupted", e);
			}

			try {
				CallQueryResult result = callService.queryCalls(callQuery);
				List<Call> callList = result.getCall();
				if (callList.isEmpty()) {
					throw new RuntimeException("call query did not return a text");
				}

				for (Call call : callList) {
					Result finalResult = call.getFinalResult();
					String toNumber = call.getToNumber().getValue();
					if (finalResult != null) {
						if (!finalResult.equals(Result.UNDIALED)) {
							if (call.getBatchId().equals(batchId)) {
								toNumbers.remove(toNumber);
							}
							LOG.info("batchId: " + call.getBatchId() + " with toNumber: " + toNumber);
							if (toNumbers.isEmpty()) {
								return true;
							}
						}
					}
				}
			}
			catch (ServiceFault e) {
				throw new RuntimeException("exception querying for texts", e);
			}
		}
		return false;
	}
	
	private Calendar getCalendarFromHour(int hour) {
		Calendar calendar = Calendar.getInstance();
		calendar.set(Calendar.HOUR_OF_DAY, hour);
		return calendar;
	}
	
	private Calendar getCalendarWeeksFromNow(int numWeeks) {
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.WEEK_OF_YEAR, numWeeks);
		return calendar;
	}
	
	private void sendReminders(long broadcastId) throws ServiceFault {
		List<Object> toNumbers1 = propertiesConfig.getList("contacts_batch_1");
		
		long septBatchId = sendBatch("Weekly PTA Reminders", toNumbers1, broadcastId);
		
		// note, this only checks that one set of reminders went out.
		// presumably, the application code could check every week.
		// or sign up for post-back notifications.
		boolean response = pollForResponse(broadcastId, toNumbers1, septBatchId);
		
		if (!response) {
			LOG.log(Level.WARNING, "did not receive postive reponse from batch: " + septBatchId);
		}
		else {
			LOG.log(Level.INFO, "received postive reponse from batch: " + septBatchId);
		}
	}
	
	private void cleanup() {
		if (scheduleId != null) {
			IdRequest request = new IdRequest();
			request.setId(scheduleId);
			try {
				broadcastService.deleteBroadcastSchedule(request);
			}
			catch (ServiceFault e) {
				throw new RuntimeException("exception deleting schedule", e);
			}
			
			FaultCode getScheduleFault = null;
			try {
				broadcastService.getBroadcastSchedule(request);
				throw new RuntimeException("schedule delete failed");
			}
			catch (ServiceFault e) {
				getScheduleFault = e.getFaultInfo().getFaultCode();
				if (getScheduleFault != FaultCode.NOT_FOUND) {
					throw new RuntimeException("unexpected fault " + getScheduleFault);
				}
			}
		}
	}
	
	/**
	 * Exercises the concept of sending two batches of notifications
	 * but reusing the same broadcast configuration.
	 * @throws Exception
	 */
	public void run() throws Exception {
		
		// create sound
		long liveSoundId = createSound(PTA_REMINDER_LIVE_SOUND);
		long machineSoundId = createSound(PTA_REMINDER_MACHINE_SOUND);
		
		// schedule weekly batch
		String fromNumber = "12132212289";
		long broadcastId = createWeeklyBroadcast(liveSoundId, machineSoundId, fromNumber);
		
		// kick off the broadcasts
		sendReminders(broadcastId);
		
		cleanup();
	}
	
	public static void main(String[] args) {
		try {
			new WeeklyPtaVoiceBroadcastSample().run();
		}
		catch (Exception e) {
			LOG.log(Level.SEVERE, "exception running " + WeeklyPtaVoiceBroadcastSample.class.getName(), e);
		}
	}
}
