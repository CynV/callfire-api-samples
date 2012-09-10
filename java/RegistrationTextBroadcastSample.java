import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.namespace.QName;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;

import com.callfire.api.data.Broadcast;
import com.callfire.api.data.BroadcastSchedule;
import com.callfire.api.data.DayOfWeek;
import com.callfire.api.data.LocalTimeZoneRestriction;
import com.callfire.api.data.Result;
import com.callfire.api.data.Text;
import com.callfire.api.data.TextBroadcastConfig;
import com.callfire.api.data.ToNumber;
import com.callfire.api.service.wsdl.BroadcastServicePortType;
import com.callfire.api.service.wsdl.ServiceFault;
import com.callfire.api.service.wsdl.TextServicePortType;
import com.callfire.api.service.wsdl.http.soap12.CallFireApi;
import com.callfire.api.service.wsdl.http.soap12.CallFireApi.ServiceState;
import com.callfire.api.service.xsd.ActionQuery;
import com.callfire.api.service.xsd.BroadcastCommand;
import com.callfire.api.service.xsd.BroadcastRequest;
import com.callfire.api.service.xsd.ControlBroadcast;
import com.callfire.api.service.xsd.CreateBroadcastSchedule;
import com.callfire.api.service.xsd.CreateContactBatch;
import com.callfire.api.service.xsd.FaultCode;
import com.callfire.api.service.xsd.IdRequest;
import com.callfire.api.service.xsd.TextQueryResult;

/**
 * Example implementation of using CallFire's APIs to send
 * class registration reminders by using text broadcasts
 * to students at a university. 
 * 
 * @author ross
 */
public class RegistrationTextBroadcastSample {
	private static final Logger LOG = Logger.getLogger(RegistrationTextBroadcastSample.class.getName());
	
	private final String user;
	private final String password;
	private final int maxWaitMs;
	
	private final CallFireApi callFireApi;
	private final BroadcastServicePortType broadcastService;
	private final TextServicePortType textService;
	private final PropertiesConfiguration propertiesConfig;
	private Long scheduleId;
	
	public RegistrationTextBroadcastSample() throws ConfigurationException {
		propertiesConfig = new PropertiesConfiguration("sample.properties");
		user = propertiesConfig.getString("user");
		password = propertiesConfig.getString("password");
		maxWaitMs = propertiesConfig.getInt("max_wait_ms");
		callFireApi = new CallFireApi(user, password, ServiceState.DEVELOPMENT);
		broadcastService = callFireApi.getBroadcastServicePort();
		textService = callFireApi.getTextServicePort();
	}
	
	private long createBroadcast(final String textMsg) throws ServiceFault {
		TextBroadcastConfig config = new TextBroadcastConfig();
		config.setMessage(textMsg);

		Broadcast broadcast = new Broadcast();
		broadcast.setTextBroadcastConfig(config);
		broadcast.setName("student registration notification broadcast");
		BroadcastRequest createRequest = new BroadcastRequest();
		createRequest.setBroadcast(broadcast);
		return broadcastService.createBroadcast(createRequest);
	}
	
	private long createScheduledBroadcast(final String textMsg) throws ServiceFault {
		Broadcast broadcast = new Broadcast();
		
		TextBroadcastConfig config = new TextBroadcastConfig();
		config.setMessage(textMsg);
		
		// setting the local timezone restriction is a good idea to prevent
		// customers from getting messages outside of normal business
		// hours in their local timezone (determined by area code).
		LocalTimeZoneRestriction restriction = new LocalTimeZoneRestriction();
		restriction.setBeginTime(getCalendarFromHour(8)); // 8 am
		restriction.setEndTime(getCalendarFromHour(18));  // 6 pm
		config.setLocalTimeZoneRestriction(restriction);
		broadcast.setTextBroadcastConfig(config);
		
		broadcast.setName("student registration notification broadcast");
		BroadcastRequest createRequest = new BroadcastRequest();
		createRequest.setBroadcast(broadcast);
		Long broadcastId = broadcastService.createBroadcast(createRequest);

		CreateBroadcastSchedule create = new CreateBroadcastSchedule();
		BroadcastSchedule schedule = new BroadcastSchedule();

		schedule.setTimeZone(TimeZone.getDefault().getID());
		schedule.getDaysOfWeek().addAll(Arrays.asList(DayOfWeek.values()));
		schedule.setStartTimeOfDay(getCalendarFromHour(8)); // 8 am
		schedule.setStopTimeOfDay(getCalendarFromHour(18)); // 6 pm
		
		create.setBroadcastId(broadcastId);
		create.setBroadcastSchedule(schedule);
		scheduleId = broadcastService.createBroadcastSchedule(create);

		return broadcastId;
	}

	private long sendBatch(String name, Map<String, Map<QName, String>> toNumbers, long broadcastId)
			throws ServiceFault {
		Long batchId = broadcastService.createContactBatch(createBatch(name, toNumbers, broadcastId));
		
		// start the broadcast
		ControlBroadcast control = new ControlBroadcast();
		control.setId(broadcastId);
		control.setCommand(BroadcastCommand.START);
		broadcastService.controlBroadcast(control);
		return batchId;
	}

	private CreateContactBatch createBatch(String name, Map<String, Map<QName, String>> toNumbers, Long broadcastId) {
		CreateContactBatch createBatch = new CreateContactBatch();
		createBatch.setName(name);
		createBatch.setBroadcastId(broadcastId);
		for (String toNumber : toNumbers.keySet()) {
			ToNumber toNumberElem = new ToNumber();
			toNumberElem.setValue(toNumber);
			toNumberElem.getOtherAttributes().putAll(toNumbers.get(toNumber));
			createBatch.getToNumber().add(toNumberElem);
		}
		createBatch.setScrubBroadcastDuplicates(false);
		return createBatch;
	}

	/**
	 * @param broadcastId
	 * @param toNumbers
	 * @return true if Result.SENT is received for each specified toNumber.
	 */
	private boolean pollForResponse(long broadcastId, Collection<Object> toNumbers, Long batchId) {
		LOG.info("polling for responses for batch: " + batchId);
		
		final int sleepInterval = 3000;
		int totalWait = 0;

		ActionQuery textQuery = new ActionQuery();
		textQuery.setBroadcastId(broadcastId);

		while (totalWait < maxWaitMs) {
			try {
				Thread.sleep(sleepInterval);
				totalWait += sleepInterval;
			}
			catch (InterruptedException e) {
				LOG.log(Level.FINE, "interrupted", e);
			}

			try {
				LOG.info("polling for text status");
				TextQueryResult result = textService.queryTexts(textQuery);
				List<Text> textList = result.getText();
				if (textList.isEmpty()) {
					throw new RuntimeException("call query did not return a text");
				}

				for (Text text : textList) {
					Result finalResult = text.getFinalResult();
					if (finalResult != null) {
						if (finalResult.equals(Result.SENT)) {
							String toNumber = text.getToNumber().getValue();
							if (text.getBatchId().equals(batchId)) {
								toNumbers.remove(toNumber);
							}
							LOG.info("batchId: " + text.getBatchId() + " with toNumber: " + toNumber);
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
	
	private void sendFallRegistrationBatch(long broadcastId, List<Object> toNumbers) throws ServiceFault {
		
		Map<String, Map<QName, String>> fallContacts = new HashMap<String, Map<QName, String>>();
		
		for (Object toNumber : toNumbers) {
			Map<QName, String> attributes = new HashMap<QName, String>();
			attributes.put(new QName("regTimeWindow"), "9/9/2012 at 12:30-1:30pm");
			attributes.put(new QName("regLocation"), "school gym");
			fallContacts.put((String)toNumber, attributes);
		}
		
		// send batch for fall 2012 students
		Long fallBatchId = sendBatch("Fall Batch", fallContacts, broadcastId);
		boolean response = pollForResponse(broadcastId, toNumbers, fallBatchId);

		if (!response) {
			LOG.log(Level.WARNING, "did not receive postive reponse from batch: " + fallBatchId);
		}
		else {
			LOG.log(Level.INFO, "received postive reponse from batch: " + fallBatchId);
		}
	}

	private void sendWinterRegistrationBatch(long broadcastId, List<Object> toNumbers) throws ServiceFault {
		Map<String, Map<QName, String>> winterContacts = new HashMap<String, Map<QName, String>>();
		
		for (Object toNumber : toNumbers) {
			Map<QName, String> attributes = new HashMap<QName, String>();
			attributes.put(new QName("regTimeWindow"), "12/19/2012 at 12:30-1:30pm");
			attributes.put(new QName("regLocation"), "school gym");
			winterContacts.put((String)toNumber, attributes);
		}
		
		// send another batch for winter 2012 students
		// reusing the existing broadcast configuration.
		Long winterBatchId = sendBatch("Winter Batch", winterContacts, broadcastId);
		boolean response = pollForResponse(broadcastId, toNumbers, winterBatchId);

		if (!response) {
			LOG.log(Level.WARNING, "did not receive postive reponse from batch: " + winterBatchId);
		}
		else {
			LOG.log(Level.INFO, "received postive reponse from batch: " + winterBatchId);
		}
	}
	
	/**
	 * Exercises the concept of sending two batches of notifications
	 * but reusing the same broadcast configuration.
	 * @throws ServiceFault
	 */
	private void sendFallThenWinterBatches() throws ServiceFault {
		
		String textMessage = "Welcome to the 2012 school year! "
				+ "Your registration window is ${regTimeWindow} at the ${regLocation}.";
		long broadcastId = createBroadcast(textMessage);
		
		List<Object> fallBatch = propertiesConfig.getList("contacts_batch_1");
		
		// send a batch of notifications to 
		// the fall class of students
		sendFallRegistrationBatch(broadcastId, fallBatch);
		
		List<Object> winterBatch = propertiesConfig.getList("contacts_batch_2");
		
		// now, we can reuse the same broadcast 
		// to send another batch, presumably for
		// students that start in the winter
		// (assume some weeks have passed)
		sendWinterRegistrationBatch(broadcastId, winterBatch);
	}
	
	/**
	 * Exercises the ability to schedule the start and stop times 
	 * as well as the days of the week that a broadcast can run. 
	 * @throws ServiceFault
	 */
	private void sendScheduledFallBatch() throws ServiceFault {
		
		String textMessage = "Welcome to the 2012 school year! "
				+ "Your registration window is ${regTimeWindow} at the ${regLocation}.";
		long broadcastId = createScheduledBroadcast(textMessage);
		
		List<Object> scheduledBatch = propertiesConfig.getList("contacts_batch_3");
		
		// send the same fall registration batch, but
		// this time it's constrained by the broadcast schedule
		// and local a timezone restriction.
		sendFallRegistrationBatch(broadcastId, scheduledBatch);
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
	
	public void run() throws Exception {
		sendFallThenWinterBatches();
		sendScheduledFallBatch();
		
		// delete the scehdule
		cleanup();
	}
	
	public static void main(String[] args) {
		try {
			new RegistrationTextBroadcastSample().run();
		}
		catch (Exception e) {
			LOG.log(Level.SEVERE, "exception running " + RegistrationTextBroadcastSample.class.getName(), e);
		}
	}
}
