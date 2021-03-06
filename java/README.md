# Java API-Samples #
The following API samples are included here. Before running these samples, be sure to modify the file `sample.properties` with valid credentials and contacts. Note that all samples are configured to connect to CallFire's developer API. They will need to be modified to use the production API before being used in any real situation.

### Running the Sample Clients ###
1. Modify `sample.properties`
1. Execute the included run script followed by the name of the sample client to be run. For example: `./run.sh SimpleTextBroadcastSample`

## Text Broadcast ##
### SimpleTextBroadcastSample ###
A very simple introduction to sending a text message using CallFire's API.

### RegistrationTextBroacastSample ###
A more involved example created around the fictitious story of a university that needs to notify students of fall class registration details. 

## Voice Broadcast ##
### SimpleTextBroadcastSample ###
A very simple introduction to sending a voice broadcast using CallFire's API.

### WeeklyPtaVoiceBroadcastSample ###
A more involved example created around the fictitious story of a K-12 school that needs to send parents weekly PTA meeting reminders.

## Polling versus Postbacks ##
It is possible to register for notifications; however, all of the samples currently use a polling mechanism to check for status. Please go to http://callfire.com/help to request help using a postback notification mechanism.
