<?php
require 'lib/PestXML.php';

$pest = new PestXML('http://dev.callfire.com/api/1.0/rest');
$pest->setupAuth("valid_username", "valid_password");

// note the "to" element should be a comma-separated list
// of valid contact numbers where the text message will
// be sent.
$params = array(
		'message' => 'This is a test message',
		'to' => 'valid_number1,valid_number2...'
);

$thingsXml = $pest->post('/text/send', $params);

// get the broadcastId from the post
$broadcastId = (string) $thingsXml->children('r', true)->Id;

// TODO should poll and verify the status of the response
$result = $pest->get('/text/index?broadcastid=' . $broadcastId);

print_r($result);

?>

