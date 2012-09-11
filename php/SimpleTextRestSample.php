<?php
require 'lib/PestXML.php';

$pest = new PestXML('http://dev.callfire.com/api/1.0/rest');
$pest->setupAuth("valid_username", "valid_password");

$params = array(
		'message' => 'This is a test message',
		'to' => '12132212227,12132212228'
);

$thingsXml = $pest->post('/text/send', $params);

// get the broadcastId from the post
$broadcastId = (string) $thingsXml->children('r', true)->Id;

// TODO should poll and verify the status of the response
$result = $pest->get('/text/index?broadcastid=' . $broadcastId);

print_r($result);

?>

