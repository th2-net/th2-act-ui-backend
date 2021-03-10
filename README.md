# Message Sender Backend

# Overview


# API

### REST

#### GET
`http://localhost:8081/dictionaries` - returns a list of dictionaries names in current schema

`http://localhost:8081/{dictionary}` - returns a list of message templates names with the specified dictionary name

`http://localhost:8080/{dictionary}/{message}` - returns a single message template with the specified dictionary name and message name

`http://localhost:8080/sessions` - returns a list of sessionAlias from boxes with th2-conn type in current schema

`http://localhost:8080/acts` - returns a list of boxes names from boxes with th2-act type in current schema

`http://localhost:8080/services` - returns a list of services names. Service name contains `act name`:`service name`. 

`http://localhost:8080/services/{act name}` - returns a list of services names in the specified `act`. Service name contains `act name`:`service name`.

`http://localhost:8080/service/{name}` - returns a service description with the specified service `name`

`http://localhost:8080/json_schema/{name}` - returns a json schema from .proto file that contains the specified service `name`
- `method` - text, specific method name. if not specified, all schemas for this service will be returned.



#### POST
`http://localhost:8080/message` - send message to codec. returns a parent event id

- `session` - text, session alias  **Required**. Example: `test02fix10`
- `dictionary` - text, name of dictionary for parsing the message  **Required**. Example: `fix50-test`
- `messageType` - text, type of the sending message  **Required**. Example: `NewOrderSingle`

Parent event id: 
```
{
  "parentEvent": "5814945e-5963-11eb-8810-4bd966db93a9"
}
```

Message send request: 
```
 {
     "OrderQty": "10",
     "OrdType": "2",
     "ClOrdID": "2990136",
     "SecurityIDSource": "8",
     "OrderCapacity": "A",
     "TransactTime": "2020-11-17T12:30:29.901",
     "AccountType": "1",
     "trailer": {
       "CheckSum": "199"
     },
     "Side": "1",
     "Price": "10",
     "TradingParty": {
       "NoPartyIDs": [
         {
           "PartyRole": "76",
           "PartyID": "test01FIX04",
           "PartyIDSource": "D"
         },
         {
           "PartyRole": "3",
           "PartyID": "0",
           "PartyIDSource": "P"
         },
         {
           "PartyRole": "122",
           "PartyID": "0",
           "PartyIDSource": "P"
         },
         {
           "PartyRole": "12",
           "PartyID": "3",
           "PartyIDSource": "P"
         }
       ]
     },
     "SecurityID": "5221001",
     "header": {
       "BeginString": "FIXT.1.1",
       "SenderCompID": "test01FIX04",
       "SendingTime": "2020-11-17T09:30:30.785",
       "TargetCompID": "FGW",
       "MsgType": "D",
       "MsgSeqNum": "6",
       "BodyLength": "243"
     },
     "DisplayQty": "10"
 }

```

`http://localhost:8080/method` - call gRPC method with specified message. returns a response message 

- `fullServiceName` - text, name of service whose method we call  **Required**. Example: `act:Act`
- `methodName` - text, name of calling method  **Required**. Example: `sendMessage`

If the message contains the field `parentEventId` then it will be attached to it otherwise the message will be attached to the generated event. 

Response message:
```
{
"message": "{\n  \"status\": {\n    \"status\": \"SUCCESS\",\n    \"message\": \"\"\n  },\n  \"checkpointId\": {\n    \"id\": \"e365e960-7163-11eb-ae4a-85aa72af0f35\",\n    \"sessionAliasToDirectionCheckpoint\": {\n    }\n  }\n}"
}
```

Response message with error:
```
{
"message": "{\n  \"status\": {\n    \"status\": \"ERROR\",\n    \"message\": \"Send message failed. See the logs.\"\n  }\n}"
}
```

Method call data:

```
{
    "message": {
      "metadata": {
        "id": {
          "connectionId": {
            "sessionAlias": "test01fix04"
          },
          "direction": "FIRST",
          "sequence": "0"
        },
        "timestamp": "2021-02-17T10:57:09.163392Z",
        "messageType": "ExecutionReport",
        "properties": {
        }
      },
      "fields": {
        "ExecID": {
          "simpleValue": "E03uSpnM7DXb"
        },
        "OrderQty": {
          "simpleValue": "10"
        },
        "LastQty": {
          "simpleValue": "10"
        },
        "OrderID": {
          "simpleValue": "003uSudOgtzd"
        },
        "TransactTime": {
          "simpleValue": "2020-11-19T14:09:52.924218"
        },
        "GroupID": {
          "simpleValue": "0"
        },
        "trailer": {
          "messageValue": {
            "fields": {
              "CheckSum": {
                "simpleValue": "102"
              }
            }
          }
        },
        "Side": {
          "simpleValue": "2"
        },
        "OrdStatus": {
          "simpleValue": "2"
        },
        "TimeInForce": {
          "simpleValue": "0"
        },
        "SecurityID": {
          "simpleValue": "5221002"
        },
        "ExecType": {
          "simpleValue": "F"
        },
        "TradeLiquidityIndicator": {
          "simpleValue": "R"
        },
        "LastLiquidityInd": {
          "simpleValue": "2"
        },
        "LeavesQty": {
          "simpleValue": "0"
        },
        "CumQty": {
          "simpleValue": "10"
        },
        "LastPx": {
          "simpleValue": "100"
        },
        "TypeOfTrade": {
          "simpleValue": "2"
        },
        "TrdMatchID": {
          "simpleValue": "IHQDL1RPDZ"
        },
        "OrdType": {
          "simpleValue": "2"
        },
        "ClOrdID": {
          "simpleValue": "9279422"
        },
        "SecurityIDSource": {
          "simpleValue": "8"
        },
        "LastMkt": {
          "simpleValue": "XLOM"
        },
        "OrderCapacity": {
          "simpleValue": "A"
        },
        "AccountType": {
          "simpleValue": "1"
        },
        "Price": {
          "simpleValue": "100"
        },
        "MDEntryID": {
          "simpleValue": "003uSudOgtzd"
        },
        "TradingParty": {
          "messageValue": {
            "fields": {
              "NoPartyIDs": {
                "listValue": {
                  "values": [
                    {
                      "messageValue": {
                        "fields": {
                          "PartyRole": {
                            "simpleValue": "76"
                          },
                          "PartyID": {
                            "simpleValue": "test02FIX10"
                          },
                          "PartyIDSource": {
                            "simpleValue": "D"
                          }
                        }
                      }
                    },
                    {
                      "messageValue": {
                        "fields": {
                          "PartyRole": {
                            "simpleValue": "17"
                          },
                          "PartyID": {
                            "simpleValue": "test01"
                          },
                          "PartyIDSource": {
                            "simpleValue": "D"
                          }
                        }
                      }
                    },
                    {
                      "messageValue": {
                        "fields": {
                          "PartyRole": {
                            "simpleValue": "3"
                          },
                          "PartyID": {
                            "simpleValue": "0"
                          },
                          "PartyIDSource": {
                            "simpleValue": "P"
                          }
                        }
                      }
                    },
                    {
                      "messageValue": {
                        "fields": {
                          "PartyRole": {
                            "simpleValue": "122"
                          },
                          "PartyID": {
                            "simpleValue": "0"
                          },
                          "PartyIDSource": {
                            "simpleValue": "P"
                          }
                        }
                      }
                    },
                    {
                      "messageValue": {
                        "fields": {
                          "PartyRole": {
                            "simpleValue": "12"
                          },
                          "PartyID": {
                            "simpleValue": "3"
                          },
                          "PartyIDSource": {
                            "simpleValue": "P"
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          }
        },
        "header": {
          "messageValue": {
            "fields": {
              "BeginString": {
                "simpleValue": "FIXT.1.1"
              },
              "SenderCompID": {
                "simpleValue": "FGW"
              },
              "SendingTime": {
                "simpleValue": "2020-11-19T14:09:52.927347"
              },
              "TargetCompID": {
                "simpleValue": "test02DC10"
              },
              "ApplVerID": {
                "simpleValue": "9"
              },
              "MsgType": {
                "simpleValue": "8"
              },
              "MsgSeqNum": {
                "simpleValue": "5671"
              },
              "OnBehalfOfCompID": {
                "simpleValue": "test02FIX10"
              },
              "BodyLength": {
                "simpleValue": "440"
              }
            }
          }
        },
        "DisplayQty": {
          "simpleValue": "0"
        }
      }
    }
}
```

# Configuration
schema component description example (act-ui-backend.yml):
```
apiVersion: th2.exactpro.com/v1
kind: Th2CoreBox
metadata:
  name: rpt-data-provider
spec:
  image-name: ghcr.io/th2-net/th2-rpt-data-provider
  image-version: 2.2.5 // change this line if you want to use a newer version
  type: th2-rpt-data-provider
  custom-config:
    hostname: "localhost"
    port: 8080
    responseTimeout: 6000 // maximum request processing time in milliseconds

    serverCacheTimeout: 60000 // cached event lifetime in milliseconds
    clientCacheTimeout: 60 // cached event lifetime in milliseconds

    ioDispatcherThreadPoolSize: 10 // thread pool size for blocking database calls
    codecResponseTimeout: 6000 // if a codec doesn't respond in time, requested message is returned with a 'null' body
    codecCacheSize: 100 // size of the internal cache for parsed messages
    checkRequestsAliveDelay: 2000 // response channel check interval in milliseconds
    
    schemaXMLLink: "http://th2-qa:30000/editor/backend/schema/qa-test-script" // link to the desired schema
    protoCompileDirectory: "src/main/resources/protobuf" // directory for compiling proto files
    namespace: "th2-qa" // namespace for sending grpc messages
    actTypes: ["th2-act"] // the types of services the *acts* method will look for
    
  pins: // pins are used to communicate with codec components to parse message data
    - name: to_codec
      connection-type: mq
      attributes:
        - to_codec
        - raw
        - publish
    - name: from_codec
      connection-type: mq
      attributes:
        - from_codec
        - parsed
        - subscribe
  extended-settings:
    chart-cfg:
      ref: schema-stable
      path: custom-component
    service:
      enabled: false
      nodePort: '31275'
    envVariables:
      JAVA_TOOL_OPTIONS: "-XX:+ExitOnOutOfMemoryError -Ddatastax-java-driver.advanced.connection.init-query-timeout=\"5000 milliseconds\""
    resources:
      limits:
        memory: 2000Mi
        cpu: 600m
      requests:
        memory: 300Mi
        cpu: 50m

```
