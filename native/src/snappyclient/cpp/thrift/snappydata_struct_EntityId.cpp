/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include <iosfwd>

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/cxxfunctional.h>
#include "snappydata_struct_EntityId.h"

#include <algorithm>
#include <ostream>

#include <thrift/TToString.h>

namespace io { namespace snappydata { namespace thrift {


EntityId::~EntityId() noexcept {
}


void EntityId::__set_id(const int64_t val) {
  this->id = val;
}

void EntityId::__set_type(const int8_t val) {
  this->type = val;
}

void EntityId::__set_connId(const int64_t val) {
  this->connId = val;
}

void EntityId::__set_token(const std::string& val) {
  this->token = val;
}

uint32_t EntityId::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_id = false;
  bool isset_type = false;
  bool isset_connId = false;
  bool isset_token = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->id);
          isset_id = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_BYTE) {
          xfer += iprot->readByte(this->type);
          isset_type = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_I64) {
          xfer += iprot->readI64(this->connId);
          isset_connId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->token);
          isset_token = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      default:
        xfer += iprot->skip(ftype);
        break;
    }
    xfer += iprot->readFieldEnd();
  }

  xfer += iprot->readStructEnd();

  if (!isset_id)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_type)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_connId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_token)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t EntityId::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("EntityId");

  xfer += oprot->writeFieldBegin("id", ::apache::thrift::protocol::T_I64, 1);
  xfer += oprot->writeI64(this->id);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("type", ::apache::thrift::protocol::T_BYTE, 2);
  xfer += oprot->writeByte(this->type);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("connId", ::apache::thrift::protocol::T_I64, 3);
  xfer += oprot->writeI64(this->connId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("token", ::apache::thrift::protocol::T_STRING, 4);
  xfer += oprot->writeBinary(this->token);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(EntityId &a, EntityId &b) {
  using ::std::swap;
  swap(a.id, b.id);
  swap(a.type, b.type);
  swap(a.connId, b.connId);
  swap(a.token, b.token);
}

EntityId::EntityId(const EntityId& other496) {
  id = other496.id;
  type = other496.type;
  connId = other496.connId;
  token = other496.token;
}
EntityId::EntityId( EntityId&& other497) noexcept {
  id = std::move(other497.id);
  type = std::move(other497.type);
  connId = std::move(other497.connId);
  token = std::move(other497.token);
}
EntityId& EntityId::operator=(const EntityId& other498) {
  id = other498.id;
  type = other498.type;
  connId = other498.connId;
  token = other498.token;
  return *this;
}
EntityId& EntityId::operator=(EntityId&& other499) noexcept {
  id = std::move(other499.id);
  type = std::move(other499.type);
  connId = std::move(other499.connId);
  token = std::move(other499.token);
  return *this;
}
void EntityId::printTo(std::ostream& out) const {
  using ::apache::thrift::to_string;
  out << "EntityId(";
  out << "id=" << to_string(id);
  out << ", " << "type=" << to_string(type);
  out << ", " << "connId=" << to_string(connId);
  out << ", " << "token=" << to_string(token);
  out << ")";
}

}}} // namespace
