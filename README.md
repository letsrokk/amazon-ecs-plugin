# Amazon ECS and Fargate Plugin for Jenkins

## About

This jenkins plugin do use [Amazon Elastic Container Service](http://docs.aws.amazon.com/AmazonECS/latest/developerguide/Welcome.html) and [Amazon Fargate](https://aws.amazon.com/fargate/) to host jobs execution inside docker containers.

## Documentation and Installation

Please find the documentation on the [Jenkins Wiki page Amazon EC2 Container Service Plugin](https://wiki.jenkins-ci.org/display/JENKINS/Amazon+EC2+Container+Service+Plugin).

## Fork Notes

- this fork implements support for [Amazon Fargate](https://aws.amazon.com/fargate/)
- merged changes for ECS EC2 Autoscaling by [cbamelis](https://github.com/cbamelis/amazon-ecs-plugin/tree/autoscaling)
- merged changes from pull-request fixing [limit of slaves per template](https://github.com/jenkinsci/amazon-ecs-plugin/pull/48) 

## Build

```
$ mvn clean compile hpi:hpi
```