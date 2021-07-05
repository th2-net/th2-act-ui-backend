# Act-ui backend

# Overview
This is a backend component for the `act-ui` web app. Act-ui backend provides autocompletion data and enables communication with th2 infrastructure. To fucntion properly, conn (or codec) components need to be connected to it.


`infra-mgr 1.5.1` is required.

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
`http://localhost:8080/message` - send message to codec.

- `session` - text, session alias  **Required**. Example: `test02fix10`
- `dictionary` - text, name of dictionary for parsing the message  **Required**. Example: `fix50-test`
- `messageType` - text, type of the sending message  **Required**. Example: `NewOrderSingle`

Response: 
```
{
  "eventId": "5814945e-5963-11eb-8810-4bd966db93a9",
  "session": "...",
  "dictionary": "...",
  "messageType": "..."
}
eventId - status event id.
session, dictionary, messageType - same as request.
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

`http://localhost:8080/method` - call gRPC method with specified message. 

- `fullServiceName` - text, name of service whose method we call  **Required**. Example: `act:Act`
- `methodName` - text, name of calling method  **Required**. Example: `sendMessage`


Response:
```
{
    "eventId": "5814945e-5963-11eb-8810-4bd966db93a9",
    "methodName": "...",
    "fullServiceName": "...",
    "responseMessage": "{\n  \"status\": {\n    \"status\": \"SUCCESS\",\n    \"message\": \"\"\n  },\n  \"checkpointId\": {\n    \"id\": \"e365e960-7163-11eb-ae4a-85aa72af0f35\",\n    \"sessionAliasToDirectionCheckpoint\": {\n    }\n  }"
}
eventId - status event id.  
methodName, fullServiceName - same as request.
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
  name: act-ui-backend
spec:
  image-name: ghcr.io/th2-net/th2-act-ui-backend
  image-version: 0.3.0 // change this line if you want to use a newer version
  type: th2-rpt-data-provider
  custom-config:
    hostname: "localhost"
    port: 8080
    responseTimeout: 6000 // maximum request processing time in milliseconds

    clientCacheTimeout: 60 // cached event lifetime in milliseconds
    ioDispatcherThreadPoolSize: 10 // thread pool size for blocking database calls
        
    schemaDefinitionLink: "" // link of schema definition
    protoCompileDirectory: "src/main/resources/protobuf" // directory for compiling proto files
    namespace: "th2-qa" // namespace for sending grpc messages
    actTypes: ["th2-act"] // the types of services the *acts* method will look for
    schemaCacheExpiry: 86400 // schemaXML cache clearing frequency

    protoCacheExpiry: 3600 // compiled proto schema cache clearing frequency
    protoCacheSize: 100 // compiled proto schema cache size
    getSchemaRetryCount: 10 // number of retries when requesting an xml schema
    getSchemaRetryDelay: 1 // delay between attempts to load xml schema
    schemaDescriptorsLink: "" // link to the api to get the base64 proto schema descriptors for the service
    descriptorsCacheExpiry: 10 // service descriptors cache clearing frequency
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
    service:
      enabled: false

    envVariables:
      JAVA_TOOL_OPTIONS: '-XX:+UseContainerSupport -XX:MaxRAMPercentage=90'

    resources:
      limits:
        memory: 700Mi
        cpu: 310m
      requests:
        memory: 300Mi
        cpu: 50m

```
