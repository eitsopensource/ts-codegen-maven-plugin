import { InjectionToken } from '@angular/core';
import { Observable } from 'rxjs/Observable';
import * as SockJS from 'sockjs-client';
import * as Stomp from '@stomp/stompjs';
import { Observer } from 'rxjs/Observer';

export interface MethodReturnTypes {
    [methodName: string]: string
}

export interface ServiceReturnTypes {
    [serviceName: string]: MethodReturnTypes
}

/**
 * path é o caminho SEM BARRA AO FINAL para o broker. Por padrão é simplesmente 'broker'
 */
export interface BrokerConfiguration {
    path: string,
    realTime: boolean,
    stompPath?: string
}

export let BROKER_CONFIGURATION = new InjectionToken<BrokerConfiguration>('broker.configuration');


/////////////////
/////////////////
/////////////////
/////////////////

type EngagedRealTimeMethods  = EngagedMethodData[];

interface EngagedMethodData {
    combinedName: string,
    args: any[],
    observable: Observable<any>,
    observer: Observer<any>
}

interface RealTimeMethodsByEntity {
    [entityName: string]: string[]
}


let stompClient: Stomp.Client;

const serviceReturnTypes: ServiceReturnTypes = {};
const realTimeMethodsByEntity: RealTimeMethodsByEntity = {};
const engagedRealTimeMethods: EngagedRealTimeMethods = [];

export function registerMethodTypes(serviceName: string, returnTypes: MethodReturnTypes) {
    serviceReturnTypes[serviceName] = returnTypes;
    Object.keys(returnTypes).forEach(methodName => {
        const entityName = returnTypes[methodName];
        if (!realTimeMethodsByEntity[entityName]) {
            realTimeMethodsByEntity[entityName] = [];
        }
        realTimeMethodsByEntity[entityName].push(serviceName + '.' + methodName);
    });
}

export function dwrWrapper(configuration: BrokerConfiguration, serviceName: string, methodName: string, ...args: any[]): Observable<any> {
    if (configuration.realTime) {
        const combinedName = serviceName + '.' + methodName;
        const observable = Observable.create((observer: Observer<any>) => {
            engagedRealTimeMethods.push({
                combinedName,
                args,
                observer,
                observable
            });
            lazyInitStompConnection(configuration).then(() => callEngagedMethod(configuration, serviceName, methodName, args, observer));
            return () => {
                delete engagedRealTimeMethods[combinedName];
            }
        });
        return observable;
    } else {
        return Observable.create((observer: Observer<any>) => {
            loadDwrIfNeeded(configuration).then(() => {
                loadServiceIfNeeded(configuration, serviceName).then(service => {
                    args.push({
                        callback(result) {
                            observer.next(result);
                            observer.complete();
                        },
                        errorHandler(message, exception) {
                            observer.error({ message, exception });
                            console.error(message, exception);
                            observer.complete();
                        }
                    });
                    service[methodName].apply(service, args);
                });
            });
        });
    }
}

function loadDwrIfNeeded(configuration: BrokerConfiguration): Promise<void> {
    return new Promise<void>(resolve => {
        if ((window as any).dwr) {
            resolve();
        } else {
            const path = `${configuration.path}/engine.js`;
            const tag: HTMLScriptElement = document.createElement('script');
            tag.src = path;
            tag.type = 'text/javascript';
            tag.onload = () => resolve();
            tag.onerror = () => resolve();
            document.body.appendChild(tag);
        }
    });
}

function loadServiceIfNeeded(configuration: BrokerConfiguration, name: string): Promise<any> {
    return new Promise<any>(resolve => {
        if ((window as any)[name]) {
            resolve((window as any)[name]);
        } else {
            const path = `${configuration.path}/interface/${name}.js`;
            const tag: HTMLScriptElement = document.createElement('script');
            tag.src = path;
            tag.type = 'text/javascript';
            tag.onload = () => resolve((window as any)[name]);
            tag.onerror = () => resolve();
            document.body.appendChild(tag);
        }
    });
}

function lazyInitStompConnection(configuration: BrokerConfiguration): Promise<void> {
    return new Promise<void>(resolve => {
        if(stompClient && stompClient.connected) {
            resolve();
        } else {
            stompClient = Stomp.over(new SockJS(configuration.stompPath) as any);
            stompClient.connect({}, () => {
                stompClient.subscribe('/topic/entities', message => handleStompMessage(configuration, message));
                resolve();
            }, () => setTimeout(() => lazyInitStompConnection(configuration), 5000));
        }
    });
}

function handleStompMessage(configuration: BrokerConfiguration, message: Stomp.Message) {
    const fullType: string = message.body;

    realTimeMethodsByEntity[fullType].forEach(combinedName => {
        const [service, method] = combinedName.split('.');
        engagedRealTimeMethods.filter(data => data.combinedName === service + '.' + method)
            .forEach(data => {
                const {args, observer} = data;
                callEngagedMethod(configuration, service, method, args, observer);
            });
    });
}

function callEngagedMethod(configuration: BrokerConfiguration, serviceName: string, methodName: string, args: any[], observer: Observer<any>) {
    loadDwrIfNeeded(configuration).then(() => {
        loadServiceIfNeeded(configuration, serviceName).then(service => {
            const callback = {
                callback(result) {
                    observer.next(result);
                },
                errorHandler(message, exception) {
                    observer.error({ message, exception });
                    console.error(message, exception);
                }
            };
            if(args[args.length - 1].callback) {
                args[args.length - 1] = callback;
            } else {
                args.push(callback);
            }
            service[methodName].apply(service, args);
        });
    });
}